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
  @org.springframework.test.context.bean.override.mockito.MockitoSpyBean AgentScopeExecutor agentExecutor;
  @Autowired RunService service;
  @Autowired dev.workbench.planning.PlanService plans;
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

    agentExecutor.execute(request);

    var components = captured.get();
    assertNotNull(components, "执行器必须发出真实装配快照");
    assertEquals(1, components.memoryMatches().size(), "装配快照必须保留同批记忆证据而非二次查库变空");
    assertEquals(memoryId, components.memoryMatches().getFirst().memoryId());
    assertEquals(1, components.memoryMatches().getFirst().version());
  }

  /**
   * 端到端单次记忆解析：{@code RunService} 创建「无显式模板」任务 → 标准 {@code workspace-analysis} 工作流 →
   * AgentScopeExecutor，全程不允许任何执行器按 runId 二次查库。
   *
   * <p>并发删除窗口精准插在「调度器已组装 Prompt、执行器尚未落装配快照」之间：删除之后按 runId 查库必然为空，
   * 因此只要落库快照仍保留同一批记忆证据，就证明整条链路只解析过一次记忆。
   */
  @Test
  void workflowRunResolvesMemoryOnceAndKeepsSnapshotAfterConcurrentDelete() throws Exception {
    String workspaceId = workspaces.createIdeaWorkspace(List.of("sort.js")).id();
    String memoryId = UUID.randomUUID().toString();
    records.save(
        new MemoryRecordEntity(
            memoryId,
            workspaceId,
            MemoryCategory.PROJECT_CONVENTION,
            "工作流冻结时的项目约定正文：排序页面保持原生脚本",
            MemorySource.USER_CONFIRMED,
            MemoryStatus.ACTIVE,
            1,
            "2026-01-01T00:00:00Z",
            "2026-01-02T00:00:00Z"));

    var capturedRequests = new java.util.concurrent.CopyOnWriteArrayList<AgentExecutionRequest>();
    var deletedBeforeAnalystExecutor = new AtomicReference<>(Boolean.FALSE);
    var emptyAssemblyRightAfterDelete = new AtomicReference<>(Boolean.FALSE);
    // 在调度器组装完 Prompt 之后、进入真实 AgentScopeExecutor 之前精准插入并发删除窗口。
    org.mockito.Mockito.doAnswer(
            invocation -> {
              AgentExecutionRequest request = invocation.getArgument(0);
              capturedRequests.add(request);
              if (request.role() == AgentRole.ANALYST
                  && deletedBeforeAnalystExecutor.compareAndSet(false, true)) {
                memories.delete(workspaceId, memoryId, 1);
                emptyAssemblyRightAfterDelete.set(
                    assembly.matchesForRun(request.context().runId()).isEmpty());
              }
              return invocation.callRealMethod();
            })
        .when(agentExecutor)
        .execute(org.mockito.ArgumentMatchers.any(AgentExecutionRequest.class));

    String runId =
        service.create(
            dev.workbench.orchestration.CreateTaskRequest.builder()
                .goal("按项目约定分析排序页面实现")
                .scenario("normal")
                .limits(12, 12, 120)
                .modelMode("mock")
                .workspace(workspaceId, null)
                .fileScopes(List.of("sort.js"))
                .memoryEnabled(true)
                .constraints(List.of("排序页面保持原生脚本"))
                .acceptanceCriteria(List.of("给出分析结论"))
                .locale("zh-CN")
                .build());

    var finished = awaitTerminal(runId);
    assertEquals(Boolean.TRUE, deletedBeforeAnalystExecutor.get(), "必须实际触发分析阶段删库窗口");
    assertEquals(
        Boolean.TRUE,
        emptyAssemblyRightAfterDelete.get(),
        "删除后按 runId 查库必然为空——这正说明执行器不能二次查库");

    // 无显式模板的任务必须被物化为标准内置工作流，绝不再落回任何遗留直跑轨。
    var plan = plans.workflowPlan(runId);
    assertNotNull(plan, "无模板新建任务必须物化标准 WorkflowPlan");
    assertEquals("workspace-analysis", plan.templateId());
    assertTrue(
        finished.status() == RunStatus.COMPLETED,
        "标准工作流应正常结束，实际状态 " + finished.status());

    var analystReq =
        capturedRequests.stream()
            .filter(r -> r.role() == AgentRole.ANALYST)
            .findFirst()
            .orElseThrow();
    assertTrue(
        analystReq.context().text().contains("工作流冻结时的项目约定正文"),
        "分析 Prompt 必须包含召回的记忆正文");
    assertNotNull(analystReq.resolvedMemories(), "分析请求必须显式携带 resolvedMemories");
    assertEquals(1, analystReq.resolvedMemories().size());
    assertEquals(memoryId, analystReq.resolvedMemories().getFirst().memoryId());

    // 真正从 H2 持久化装配表回读：即便删库发生在执行器之前，落库快照仍必须保留同批记忆 ID 与版本。
    var persisted = assemblyRecords.agentAssemblies(runId);
    assertFalse(persisted.isEmpty(), "标准工作流必须落库真实装配快照");
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

  /** 轮询运行到终态，避免测试在异步派发尚未结束时开始断言。 */
  private dev.workbench.domain.RunSnapshot awaitTerminal(String runId) throws Exception {
    for (int i = 0; i < 300; i++) {
      var snapshot = runs.runSnapshot(runId);
      if (snapshot.status() != RunStatus.PENDING && snapshot.status() != RunStatus.RUNNING)
        return snapshot;
      Thread.sleep(50);
    }
    return runs.runSnapshot(runId);
  }
}
