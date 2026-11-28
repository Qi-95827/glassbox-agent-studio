package dev.workbench.orchestration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.workbench.app.WorkbenchApplication;
import dev.workbench.bug.BugIntakeService;
import dev.workbench.bug.BugService;
import dev.workbench.domain.AgentRole;
import dev.workbench.domain.CheckpointStage;
import dev.workbench.domain.MemoryCategory;
import dev.workbench.domain.MemorySource;
import dev.workbench.domain.MemoryStatus;
import dev.workbench.domain.ModelMode;
import dev.workbench.domain.RunLimits;
import dev.workbench.domain.RunStatus;
import dev.workbench.domain.Scenario;
import dev.workbench.domain.TaskDefinition;
import dev.workbench.domain.TaskEntryKind;
import dev.workbench.framework.agent.assembly.AgentAssemblySnapshot;
import dev.workbench.framework.agent.execution.AgentExecutionRequest;
import dev.workbench.framework.agent.execution.AgentExecutor;
import dev.workbench.framework.agent.execution.AgentRuntime;
import dev.workbench.framework.context.AgentContextAssembler;
import dev.workbench.framework.control.RunControl;
import dev.workbench.framework.control.RunControlStore;
import dev.workbench.framework.memory.MemoryMatch;
import dev.workbench.framework.memory.MemoryType;
import dev.workbench.infrastructure.agentscope.AgentScopeExecutor;
import dev.workbench.memory.MemoryAssemblyAdapter;
import dev.workbench.memory.MemoryService;
import dev.workbench.persistence.entity.MemoryRecordEntity;
import dev.workbench.persistence.repository.MemoryRecordRepository;
import dev.workbench.store.AgentAssemblyRecordService;
import dev.workbench.store.RunCheckpointStore;
import dev.workbench.store.RunCreationStore;
import dev.workbench.store.RunEvidenceRecordService;
import dev.workbench.store.RunStateStore;
import dev.workbench.workspace.WorkspaceService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 单次节点尝试只能解析一次记忆：模型 Prompt 与装配快照共享同一批证据。
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
  @Autowired AgentScopeExecutor executor;
  @Autowired AgentContextAssembler contexts;
  @Autowired RunCreationStore creation;
  @Autowired RunStateStore runs;
  @Autowired RunEvidenceRecordService evidence;
  @Autowired RunCheckpointStore checkpointStore;
  @Autowired AgentAssemblyRecordService assemblyRecords;
  @Autowired RunControlStore controls;
  @Autowired WorkspaceService workspaces;
  @Autowired ObjectMapper json;

  @Test
  void resolvedEvidenceSurvivesAConcurrentDeleteBetweenPromptAndSnapshot() throws Exception {
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

    // 第三步：真正穿过生产 AgentScopeExecutor，验证写入装配快照的 memoryMatches 来自请求证据而非二次查库。
    var captured = new AtomicReference<AgentAssemblySnapshot.Components>();
    AgentRuntime runtime =
        new AgentRuntime() {
          @Override
          public void beforeModel(dev.workbench.domain.ModelKind modelName) {}

          @Override
          public void modelResult(Integer inputTokens, Integer outputTokens) {}

          @Override
          public void assemblyResolved(AgentAssemblySnapshot.Components components) {
            captured.set(components);
          }

          @Override
          public UUID beforeTool(dev.workbench.domain.ToolKind tool, int version, String fingerprint) {
            return UUID.randomUUID();
          }

          @Override
          public void check() {}

          @Override
          public void event(dev.workbench.domain.EventType type, String message) {}

          @Override
          public void toolResult(String result) {}

          @Override
          public void toolRejected(String safeMessage) {}
        };
    var context =
        new dev.workbench.framework.context.AgentInput(
            runId, "节点输入" + loaded.promptSection(), List.of());
    var request =
        new AgentExecutionRequest(
                AgentRole.ANALYST,
                UUID.randomUUID().toString(),
                context,
                null,
                List.of(),
                false,
                Scenario.NORMAL,
                ModelMode.MOCK,
                CheckpointStage.BEFORE_TOOL,
                null,
                runtime,
                null,
                null,
                List.of(),
                null,
                "zh-CN")
            .withResolvedMemories(loaded.matches());

    executor.execute(request);

    var components = captured.get();
    assertNotNull(components, "执行器必须发出真实装配快照");
    assertEquals(1, components.memoryMatches().size(), "装配快照必须保留同批记忆证据而非二次查库变空");
    assertEquals(memoryId, components.memoryMatches().getFirst().memoryId());
    assertEquals(1, components.memoryMatches().getFirst().version());
  }

  @Test
  void directRunAnalysisPathUsesSingleMemoryResolutionAndPersistsSnapshotAfterConcurrentDelete() {
    String workspaceId = workspaces.createIdeaWorkspace(List.of("sort.js")).id();
    String memoryId = UUID.randomUUID().toString();
    records.save(
        new MemoryRecordEntity(
            memoryId,
            workspaceId,
            MemoryCategory.PROJECT_CONVENTION,
            "直跑冻结时的项目约定正文：排序页面保持原生脚本",
            MemorySource.USER_CONFIRMED,
            MemoryStatus.ACTIVE,
            1,
            "2026-01-01T00:00:00Z",
            "2026-01-02T00:00:00Z"));

    UUID taskId = UUID.randomUUID();
    UUID runId = UUID.randomUUID();
    TaskDefinition task =
        new TaskDefinition(
            taskId,
            "按项目约定分析排序页面实现",
            Scenario.NORMAL,
            ModelMode.MOCK,
            workspaceId,
            "sort.js",
            false,
            null,
            true,
            List.of("排序页面保持原生脚本"),
            List.of("给出分析结论"),
            TaskEntryKind.CODE,
            List.of("sort.js"),
            Map.of(),
            Map.of(),
            null,
            "zh-CN");
    creation.createTaskAndFirstRun(
        task,
        runId,
        new RunLimits(12, 12, 6, null, 3, 120, 6, 6),
        "直跑单次记忆解析回归任务");
    runs.update(runId.toString(), RunStatus.RUNNING, null, null, 0, 0, 0L);

    var capturedRequests = new ArrayList<AgentExecutionRequest>();
    var deletedBeforeAnalystExecutor = new AtomicReference<>(Boolean.FALSE);
    // 在真实 DirectRunExecutor 组装完 Prompt 后、进入真实 AgentScopeExecutor 前精准插入并发删除窗口。
    AgentExecutor raceInjectingExecutor =
        request -> {
          capturedRequests.add(request);
          if (request.role() == AgentRole.ANALYST) {
            memories.delete(workspaceId, memoryId, 1);
            assertTrue(
                assembly.matchesForRun(runId.toString()).isEmpty(),
                "删除后再按 runId 查库必然为空——这正说明直跑执行器不能二次查库");
            deletedBeforeAnalystExecutor.set(Boolean.TRUE);
          }
          return executor.execute(request);
        };

    var handoffs = new RunHandoffFactory(evidence, json);
    var commands = mock(RunCommandService.class);
    var stateLock = new Object();
    var review =
        new RunReviewCoordinator(
            runs,
            assemblyRecords,
            evidence,
            raceInjectingExecutor,
            commands,
            handoffs,
            null,
            null,
            stateLock);
    var direct =
        new DirectRunExecutor(
            creation,
            runs,
            evidence,
            checkpointStore,
            assemblyRecords,
            raceInjectingExecutor,
            contexts,
            mock(BugService.class),
            mock(BugIntakeService.class),
            mock(RunCheckpointSupport.class),
            handoffs,
            mock(RunFailureHandler.class),
            commands,
            review,
            null,
            null,
            null,
            null);

    var snapshot = runs.runSnapshot(runId.toString());
    var control =
        new RunControl(
            runId.toString(),
            snapshot,
            controls,
            new Object(),
            Set.of(),
            Set.of(),
            ConcurrentHashMap.newKeySet(),
            Map.of(),
            Map.of());
    var state =
        new DirectRunExecutor.ExecutionState(
            runId.toString(), snapshot, task, control, CheckpointStage.BEFORE_TOOL, null, false);
    state.agentId = UUID.randomUUID().toString();
    direct.execute(state);

    assertEquals(Boolean.TRUE, deletedBeforeAnalystExecutor.get(), "必须实际触发分析阶段删库窗口");
    var analystReq =
        capturedRequests.stream()
            .filter(r -> r.role() == AgentRole.ANALYST)
            .findFirst()
            .orElseThrow();
    assertTrue(
        analystReq.context().text().contains("直跑冻结时的项目约定正文"),
        "直跑分析 Prompt 必须包含召回的记忆正文");
    assertNotNull(analystReq.resolvedMemories(), "直跑分析请求必须显式携带 resolvedMemories");
    assertEquals(1, analystReq.resolvedMemories().size());
    assertEquals(memoryId, analystReq.resolvedMemories().getFirst().memoryId());

    // 审查阶段未注入记忆，也必须显式传入空列表而非 null，绝不回退按 runId 查库。
    var reviewerReq =
        capturedRequests.stream()
            .filter(r -> r.role() == AgentRole.REVIEWER)
            .findFirst()
            .orElseThrow();
    assertNotNull(reviewerReq.resolvedMemories(), "直跑审查请求不得为 null 回退查库");
    assertTrue(reviewerReq.resolvedMemories().isEmpty());

    // 真正从 H2 持久化装配表回读：即便删库发生在执行器之前，落库快照仍必须保留同批记忆 ID 与版本。
    var persisted = assemblyRecords.agentAssemblies(runId.toString());
    assertFalse(persisted.isEmpty(), "直跑必须落库真实装配快照");
    var analystSnapshot =
        persisted.stream()
            .filter(item -> item.snapshot().components().authorization().profile() == AgentRole.ANALYST)
            .findFirst()
            .orElseThrow();
    assertEquals(
        1,
        analystSnapshot.snapshot().components().memoryMatches().size(),
        "持久化装配表中的分析快照必须保留同批记忆证据而非变空");
    assertEquals(
        memoryId, analystSnapshot.snapshot().components().memoryMatches().getFirst().memoryId());
    assertEquals(
        1, analystSnapshot.snapshot().components().memoryMatches().getFirst().version());
  }
}
