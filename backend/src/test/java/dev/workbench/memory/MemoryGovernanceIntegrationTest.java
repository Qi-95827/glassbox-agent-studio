package dev.workbench.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.workbench.app.WorkbenchApplication;
import dev.workbench.domain.MemoryCategory;
import dev.workbench.domain.MemorySource;
import dev.workbench.domain.MemoryStatus;
import dev.workbench.framework.skill.SkillDraftPort;
import dev.workbench.persistence.entity.MemoryRecordEntity;
import dev.workbench.persistence.repository.MemoryRecordRepository;
import dev.workbench.workspace.WorkspaceService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 长期记忆治理的三条硬约束。
 *
 * <p>① 正文修改后旧语义向量必须失效（否则永久沿用旧向量召回）；
 * ② 采纳候选记忆不能绕过「每工作区最多 20 条 ACTIVE」上限；
 * ③ 只有 ACTIVE 记忆可以晋升为 Skill 草稿，候选/已归档必须被拒绝。
 */
@SpringBootTest(
    classes = WorkbenchApplication.class,
    properties = {
      "spring.datasource.url=jdbc:h2:mem:memory_governance;DB_CLOSE_DELAY=-1",
      "workbench.workspace-root=${java.io.tmpdir}/agent-workbench-memory-governance"
    })
class MemoryGovernanceIntegrationTest {
  @Autowired MemoryService memories;
  @Autowired MemoryRecordRepository records;
  @Autowired WorkspaceService workspaces;
  @Autowired dev.workbench.framework.skill.SkillDraftPort skillDrafts;
  @Autowired JdbcTemplate db;

  @Test
  void updatingContentClearsTheStaleEmbeddingVector() {
    String workspaceId = workspaces.createIdeaWorkspace(List.of("sort.js")).id();
    var created = memories.create(workspaceId, MemoryCategory.PROJECT_CONVENTION, "原始正文");
    String memoryId = created.id().toString();
    // 模拟此前已回填过向量：正文变化后这两列必须一起被清空。
    db.update(
        "UPDATE memory_records SET embedding_json = ?, embedding_model = ? WHERE id = ?",
        "[0.1,0.2,0.3]",
        "mock-embedding",
        memoryId);
    assertNotNull(embeddingJson(memoryId), "前置条件：向量已存在");

    memories.update(workspaceId, memoryId, created.version(), MemoryCategory.PRECAUTION, "更新后的正文");

    assertNull(embeddingJson(memoryId), "正文修改后 embedding_json 必须被清空");
    assertNull(embeddingModel(memoryId), "正文修改后 embedding_model 必须被清空");
    assertEquals("更新后的正文", records.findById(memoryId).orElseThrow().getContent());
  }

  @Test
  void acceptingCandidateIsRejectedWhenWorkspaceAlreadyHasTwentyActiveMemories() {
    String workspaceId = workspaces.createIdeaWorkspace(List.of("sort.js")).id();
    for (int index = 0; index < 20; index++)
      seedActive(workspaceId, "已确认记忆 " + index, "2026-01-01T00:00:0" + (index % 10) + "Z");
    assertEquals(20, records.countByWorkspaceIdAndStatus(workspaceId, MemoryStatus.ACTIVE));

    var candidate =
        memories.createCandidate(
            workspaceId, MemoryCategory.PRECAUTION, "候选记忆正文", UUID.randomUUID().toString(),
            List.of(), 0.7);
    var error =
        assertThrows(
            IllegalStateException.class,
            () -> memories.accept(workspaceId, candidate.id().toString(), candidate.version()));
    assertTrue(error.getMessage().contains("20"), error.getMessage());
    assertEquals(
        MemoryStatus.CANDIDATE,
        records.findById(candidate.id().toString()).orElseThrow().getStatus(),
        "被拒绝的候选必须保持 CANDIDATE");
  }

  @Test
  void promoteRequiresActiveStatus() {
    String workspaceId = workspaces.createIdeaWorkspace(List.of("sort.js")).id();
    var active = memories.create(workspaceId, MemoryCategory.PROJECT_CONVENTION, "已确认经验");
    var promoted =
        skillDrafts.createDraft("已确认经验", memories.requireActiveForPromote(
            workspaceId, active.id().toString(), active.version()).content());
    assertNotNull(promoted);

    var candidate =
        memories.createCandidate(
            workspaceId, MemoryCategory.PRECAUTION, "候选经验正文", UUID.randomUUID().toString(),
            List.of(), 0.5);
    var candidateError =
        assertThrows(
            IllegalStateException.class,
            () ->
                memories.requireActiveForPromote(
                    workspaceId, candidate.id().toString(), candidate.version()));
    assertTrue(candidateError.getMessage().contains("ACTIVE"), candidateError.getMessage());

    // ARCHIVED：候选被忽略后同样不得晋升。
    var ignored =
        memories.createCandidate(
            workspaceId, MemoryCategory.PRECAUTION, "将被忽略的候选", UUID.randomUUID().toString(),
            List.of(), 0.5);
    memories.ignore(workspaceId, ignored.id().toString(), ignored.version());
    var archivedError =
        assertThrows(
            IllegalStateException.class,
            () ->
                memories.requireActiveForPromote(
                    workspaceId, ignored.id().toString(), ignored.version() + 1));
    assertTrue(archivedError.getMessage().contains("ACTIVE"), archivedError.getMessage());

    // DELETED：软删除后同样不得晋升。
    var deleted = memories.create(workspaceId, MemoryCategory.PRECAUTION, "将被删除的经验");
    memories.delete(workspaceId, deleted.id().toString(), deleted.version());
    var deletedError =
        assertThrows(
            IllegalStateException.class,
            () ->
                memories.requireActiveForPromote(
                    workspaceId, deleted.id().toString(), deleted.version()));
    assertTrue(deletedError.getMessage().contains("ACTIVE"), deletedError.getMessage());
  }

  private String embeddingJson(String memoryId) {
    return db.queryForObject(
        "SELECT embedding_json FROM memory_records WHERE id = ?", String.class, memoryId);
  }

  private String embeddingModel(String memoryId) {
    return db.queryForObject(
        "SELECT embedding_model FROM memory_records WHERE id = ?", String.class, memoryId);
  }

  private void seedActive(String workspaceId, String content, String time) {
    records.saveAndFlush(
        new MemoryRecordEntity(
            UUID.randomUUID().toString(),
            workspaceId,
            MemoryCategory.PROJECT_CONVENTION,
            content,
            MemorySource.USER_CONFIRMED,
            MemoryStatus.ACTIVE,
            1,
            time,
            time));
  }
}
