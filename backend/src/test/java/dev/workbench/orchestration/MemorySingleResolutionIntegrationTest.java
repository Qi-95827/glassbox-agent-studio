package dev.workbench.orchestration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import dev.workbench.app.WorkbenchApplication;
import dev.workbench.domain.AgentRole;
import dev.workbench.domain.CheckpointStage;
import dev.workbench.domain.MemoryCategory;
import dev.workbench.domain.MemorySource;
import dev.workbench.domain.MemoryStatus;
import dev.workbench.domain.ModelMode;
import dev.workbench.domain.Scenario;
import dev.workbench.domain.TaskDefinition;
import dev.workbench.domain.TaskEntryKind;
import dev.workbench.framework.agent.execution.AgentExecutionRequest;
import dev.workbench.framework.agent.execution.AgentRuntime;
import dev.workbench.framework.memory.MemoryMatch;
import dev.workbench.framework.memory.MemoryType;
import dev.workbench.memory.MemoryAssemblyAdapter;
import dev.workbench.memory.MemoryService;
import dev.workbench.persistence.entity.MemoryRecordEntity;
import dev.workbench.persistence.repository.MemoryRecordRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 单次节点尝试只能解析一次记忆：面试 Prompt 与装配快照共享同一批证据。
 *
 * <p>回归：调度器先查库拼 Prompt，执行器随后按 runId 再查一次；两次之间记忆被删除/归档时，
 * Prompt 已带正文、快照却消失，同一尝试出现两份互相矛盾的证据。
 */
@SpringBootTest(
    classes = WorkbenchApplication.class,
    properties = {
      "spring.datasource.url=jdbc:h2:mem:memory_single_resolution;DB_CLOSE_DELAY=-1",
      "workbench.workspace-root=${java.io.tmpdir}/agent-workbench-memory-single"
    })
class MemorySingleResolutionIntegrationTest {
  @Autowired WorkflowMemoryContext memoryContext;
  @Autowired MemoryService memories;
  @Autowired MemoryRecordRepository records;
  @Autowired MemoryAssemblyAdapter assembly;

  @Test
  void resolvedEvidenceSurvivesAConcurrentDeleteBetweenPromptAndSnapshot() {
    String memoryId = UUID.randomUUID().toString();
    String runId = UUID.randomUUID().toString();
    String workspaceId = UUID.randomUUID().toString();
    records.save(
        new MemoryRecordEntity(
            memoryId,
            workspaceId,
            MemoryCategory.PROJECT_CONVENTION,
            "冻结时的项目约定正文",
            MemorySource.USER_CONFIRMED,
            MemoryStatus.ACTIVE,
            1,
            "2026-01-01T00:00:00Z",
            "2026-01-02T00:00:00Z"));
    memories.recordLoad(
        runId,
        workspaceId,
        List.of(
            new MemoryMatch(
                memoryId, MemoryType.SEMANTIC, 0.9, "bm25=0.8", "冻结时的项目约定正文", 1)));

    TaskDefinition task =
        new TaskDefinition(
            UUID.randomUUID(),
            "按项目约定修改排序页面",
            Scenario.NORMAL,
            ModelMode.MOCK,
            workspaceId,
            "sort.js",
            false,
            null,
            true,
            List.of(),
            List.of(),
            TaskEntryKind.CODE,
            List.of("sort.js"),
            Map.of(),
            Map.of(),
            null,
            "zh-CN");

    // 第一步：调度器一次性解析（Prompt + 快照证据来自同一批匹配）。
    var loaded = memoryContext.loadWithEvidence(task, runId);
    assertTrue(loaded.promptSection().contains("冻结时的项目约定正文"), loaded.promptSection());
    assertEquals(1, loaded.matches().size());
    assertEquals(1, loaded.matches().getFirst().version());

    // 第二步：模拟「拼完 Prompt、落快照前」的并发删除窗口。
    memories.delete(workspaceId, memoryId, 1);
    assertTrue(
        assembly.matchesForRun(runId).isEmpty(),
        "删除后再查库必然为空——这正说明执行器不能二次查库");

    // 第三步：请求携带的同批证据仍然完整可落快照。
    var request =
        new AgentExecutionRequest(
                AgentRole.ANALYST,
                UUID.randomUUID().toString(),
                "节点输入",
                null,
                List.of(),
                false,
                Scenario.NORMAL,
                ModelMode.MOCK,
                CheckpointStage.BEFORE_TOOL,
                null,
                mock(AgentRuntime.class),
                null)
            .withResolvedMemories(loaded.matches());

    assertNotNull(request.resolvedMemories());
    assertEquals(1, request.resolvedMemories().size());
    assertEquals(memoryId, request.resolvedMemories().getFirst().memoryId());
    assertEquals(1, request.resolvedMemories().getFirst().version());
  }
}
