package dev.workbench.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.workbench.app.WorkbenchApplication;
import dev.workbench.domain.MemoryCategory;
import dev.workbench.domain.MemorySource;
import dev.workbench.domain.MemoryStatus;
import dev.workbench.framework.memory.MemoryMatch;
import dev.workbench.framework.memory.MemoryType;
import dev.workbench.persistence.entity.MemoryHistoryEntity;
import dev.workbench.persistence.entity.MemoryRecordEntity;
import dev.workbench.persistence.repository.MemoryHistoryRepository;
import dev.workbench.persistence.repository.MemoryRecordRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 冻结记忆必须「版本号与正文一致」。
 *
 * <p>回归缺陷：运行开始时冻结了第 1 版记忆，之后用户改写为第 2 版；下游节点复用冻结引用时却读当前最新正文，
 * 拼上旧版本号，于是玻璃盒里出现「v1 的标签 + v2 的正文」的谎言。这里用真实 H2 行验证：
 * 有历史版本时取回冻结版本正文；历史缺失时如实回退到当前版本号，绝不冒充旧版本。
 */
@SpringBootTest(
    classes = WorkbenchApplication.class,
    properties = {
      "spring.datasource.url=jdbc:h2:mem:memory_frozen_version;DB_CLOSE_DELAY=-1",
      "workbench.workspace-root=${java.io.tmpdir}/agent-workbench-memory-frozen"
    })
class MemoryFrozenVersionIntegrationTest {
  @Autowired MemoryService memories;
  @Autowired MemoryRecordRepository records;
  @Autowired MemoryHistoryRepository versions;
  @Autowired MemoryAssemblyAdapter assembly;

  @Test
  @org.springframework.transaction.annotation.Transactional
  void downstreamReusesTheFrozenVersionBodyEvenAfterTheMemoryIsEdited() {
    String memoryId = UUID.randomUUID().toString();
    String runId = UUID.randomUUID().toString();
    String workspaceId = UUID.randomUUID().toString();
    seedActive(memoryId, workspaceId, "冻结时的正文", 1);
    versions.save(
        new MemoryHistoryEntity(
            memoryId,
            1,
            MemoryCategory.PROJECT_CONVENTION,
            "冻结时的正文",
            MemoryStatus.ACTIVE,
            "2026-01-01T00:00:00Z"));
    memories.recordLoad(
        runId,
        workspaceId,
        List.of(new MemoryMatch(memoryId, MemoryType.SEMANTIC, 0.88, "bm25=0.7", "冻结时的正文", 1)));

    // 用户在运行中途改写：当前行变成第 2 版，历史同时保留第 1、2 版。
    assertEquals(
        1,
        records.updateIfActive(
            memoryId,
            workspaceId,
            MemoryCategory.PROJECT_CONVENTION,
            "改写后的正文",
            "2026-01-02T00:00:00Z",
            MemoryStatus.ACTIVE,
            1));
    versions.save(
        new MemoryHistoryEntity(
            memoryId,
            2,
            MemoryCategory.PROJECT_CONVENTION,
            "改写后的正文",
            MemoryStatus.ACTIVE,
            "2026-01-02T00:00:00Z"));

    var frozen = memories.frozenMatches(runId);

    assertEquals(1, frozen.size());
    assertEquals("冻结时的正文", frozen.getFirst().content(), "必须取回冻结版本正文");
    assertEquals(1, frozen.getFirst().memoryVersion(), "不得把旧版本号配新正文");
    assertEquals(2, records.findById(memoryId).orElseThrow().getVersion(), "当前行确实已被改写");

    // 装配快照必须与执行端同口径：显示 v1，而不是原始引用的字段或当前 v2。
    var snapshot = assembly.matchesForRun(runId);
    assertEquals(1, snapshot.size());
    assertEquals(1, snapshot.getFirst().version(), "快照版本必须与实际注入的冻结版本一致");
    assertEquals(memoryId, snapshot.getFirst().memoryId());
  }

  @Test
  void missingHistoryFallsBackToTheRealCurrentVersionInsteadOfTheFrozenNumber() {
    String memoryId = UUID.randomUUID().toString();
    String runId = UUID.randomUUID().toString();
    String workspaceId = UUID.randomUUID().toString();
    seedActive(memoryId, workspaceId, "当前正文", 2);
    // 冻结记录声称第 3 版，但历史里没有第 3 版（例如历史被清理）：只能如实回退到当前真实版本。
    memories.recordLoad(
        runId,
        workspaceId,
        List.of(new MemoryMatch(memoryId, MemoryType.SEMANTIC, 0.5, "vector=0.5", "当前正文", 3)));

    var frozen = memories.frozenMatches(runId);

    assertEquals(1, frozen.size());
    assertEquals("当前正文", frozen.getFirst().content());
    assertEquals(2, frozen.getFirst().memoryVersion(), "历史缺失时必须报告真实版本号");
    assertTrue(
        memories.loadForRun(runId).recorded(), "加载记录仍应存在，供下游区分「已加载」与「未加载」");
    assertEquals(2, assembly.matchesForRun(runId).getFirst().version(), "快照也必须报告真实当前版本");
  }

  @Test
  @org.springframework.transaction.annotation.Transactional
  void archivedMemoryIsNeitherInjectedNorShownAsInjectedInTheSnapshot() {
    String memoryId = UUID.randomUUID().toString();
    String runId = UUID.randomUUID().toString();
    String workspaceId = UUID.randomUUID().toString();
    seedActive(memoryId, workspaceId, "归档前正文", 1);
    memories.recordLoad(
        runId,
        workspaceId,
        List.of(new MemoryMatch(memoryId, MemoryType.SEMANTIC, 0.7, "bm25=0.6", "归档前正文", 1)));
    // 冻结后记忆被归档：执行端不会注入，装配快照也不能再声称「已注入」。
    records.save(
        new MemoryRecordEntity(
            memoryId,
            workspaceId,
            MemoryCategory.PROJECT_CONVENTION,
            "归档前正文",
            MemorySource.USER_CONFIRMED,
            MemoryStatus.ARCHIVED,
            1,
            "2026-01-01T00:00:00Z",
            "2026-01-03T00:00:00Z"));

    assertTrue(memories.frozenMatches(runId).isEmpty(), "归档记忆不得再注入");
    assertTrue(assembly.matchesForRun(runId).isEmpty(), "装配快照不得把归档记忆装成已注入");
  }

  private void seedActive(String id, String workspaceId, String content, int version) {
    records.save(entity(id, workspaceId, content, version));
  }

  private static MemoryRecordEntity entity(
      String id, String workspaceId, String content, int version) {
    return new MemoryRecordEntity(
        id,
        workspaceId,
        MemoryCategory.PROJECT_CONVENTION,
        content,
        MemorySource.USER_CONFIRMED,
        MemoryStatus.ACTIVE,
        version,
        "2026-01-01T00:00:00Z",
        "2026-01-02T00:00:00Z");
  }
}
