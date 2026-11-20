package dev.workbench.orchestration;

import static org.junit.jupiter.api.Assertions.*;

import dev.workbench.app.WorkbenchApplication;
import dev.workbench.domain.ModelMode;
import dev.workbench.domain.RunStatus;
import dev.workbench.domain.TaskEntryKind;
import dev.workbench.framework.agent.execution.AgentExecutionRequest;
import dev.workbench.framework.model.AgentModelProvider;
import dev.workbench.framework.model.AgentModelRegistry;
import dev.workbench.framework.workflow.plan.PlanNode;
import dev.workbench.framework.workflow.plan.PlanNodeSpec;
import dev.workbench.framework.workflow.plan.PlanService;
import dev.workbench.persistence.repository.PlanCandidateRepository;
import dev.workbench.planning.PlanCandidateInput;
import dev.workbench.planning.PlanCandidateModel;
import dev.workbench.planning.PlanCandidateService;
import dev.workbench.task.RunRecordService;
import dev.workbench.task.WorkflowExecutionService;
import dev.workbench.workspace.WorkspaceService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

/** 确认候选后只创建一个运行，并按节点规格装配 Agent 与工具范围。 */
@SpringBootTest(
    classes = WorkbenchApplication.class,
    properties = {
      "spring.datasource.url=jdbc:h2:mem:plan_candidate_runs;DB_CLOSE_DELAY=-1",
      "workbench.workspace-root=${java.io.tmpdir}/agent-workbench-plan-candidate-runs"
    })
@Import(PlanCandidateRunIntegrationTest.StubModel.class)
class PlanCandidateRunIntegrationTest {
  private static final List<String> FILES = List.of("index.html", "sort.js", "style.css");

  @TestConfiguration
  static class StubModel {
    @Bean
    @Primary
    PlanCandidateModel stubPlanCandidateModel() {
      return (instructions, input) -> new PlanCandidateModel.Reply(PLAN, "stub-plan", 10, 20);
    }

    @Bean
    @Primary
    AgentModelRegistry scriptedAgentModelRegistry() {
      return new AgentModelRegistry(
          List.of(
              new AgentModelProvider() {
                @Override
                public ModelMode mode() {
                  return ModelMode.MOCK;
                }

                @Override
                public io.agentscope.core.model.Model create(AgentExecutionRequest request) {
                  return new ScriptedWriteModel(
                      request.role(),
                      request.fileScopes(),
                      request.writeFiles(),
                      BubbleSortFixture.contents());
                }
              }));
    }
  }

  @Autowired PlanCandidateService candidates;
  @MockitoSpyBean WorkspaceService workspaces;
  @Autowired RunRecordService records;
  @Autowired RunService runs;
  @Autowired PlanService plans;
  @Autowired WorkflowExecutionService workflowRuns;
  @Autowired TransactionTemplate tx;
  @Autowired PlanCandidateRepository candidateStore;
  @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
  @MockitoSpyBean TaskCreationService taskCreations;

  @Test
  void confirmationCreatesOneRunAndReusesItForDuplicateConfirmation() {
    String workspace = workspaces.createIdeaWorkspace(FILES).id();
    int tasks = records.tasks().size();
    var view = ready(workspace, "制作可单步演示的冒泡排序网页", "draft-run-0001");
    int dispatches = runs.dispatchInvocationCount;

    var confirmed = candidates.confirm(view.id(), confirm(view));
    assertEquals("CONSUMED", candidates.get(view.id()).status());
    assertEquals(tasks + 1, records.tasks().size());
    assertEquals(dispatches + 1, runs.dispatchInvocationCount);

    var repeated = candidates.confirm(view.id(), confirm(view));
    assertEquals(confirmed.runId(), repeated.runId());
    assertEquals(tasks + 1, records.tasks().size(), "重复确认不能创建第二个任务");
    assertEquals(dispatches + 1, runs.dispatchInvocationCount, "重复确认不能再次派发");

    waitTerminal(confirmed.runId());
    var plan = plans.workflowPlan(confirmed.runId());
    assertEquals("plan-candidate", plan.templateId());
    assertNotNull(plan.approvedAt());
    var specs = plans.specsByNode(confirmed.runId());
    assertEquals(4, specs.size());
    var written =
        specs.values().stream()
            .flatMap(spec -> spec.writeFiles().stream())
            .collect(Collectors.toSet());
    assertEquals(Set.of("sort.js", "index.html", "style.css"), written);
  }

  @Test
  void workspaceFileChangeAfterPlanningRejectsConfirmation() {
    String workspace = workspaces.createIdeaWorkspace(FILES).id();
    int tasks = records.tasks().size();
    var view = ready(workspace, "制作可单步演示的冒泡排序网页", "draft-run-0002");
    var file = workspaces.read(workspace, "sort.js");
    workspaces.write(workspace, "sort.js", file.sha256(), "changed after planning\n");

    var error =
        assertThrows(
            IllegalStateException.class, () -> candidates.confirm(view.id(), confirm(view)));
    assertTrue(error.getMessage().contains("工作区或授权文件在规划后已变化"), error.getMessage());
    assertEquals(tasks, records.tasks().size());
    assertEquals("READY", candidates.get(view.id()).status());
  }

  @Test
  void staleCandidateCannotBeConfirmed() {
    String workspace = workspaces.createIdeaWorkspace(FILES).id();
    var first = ready(workspace, "制作可单步演示的冒泡排序网页", "draft-run-0003");
    var second = ready(workspace, "把排序网页改成支持负数与重复值", "draft-run-0003");
    assertEquals("STALE", candidates.get(first.id()).status());
    assertEquals("READY", second.status());
    assertThrows(IllegalStateException.class, () -> candidates.confirm(first.id(), confirm(first)));
  }

  @Test
  void implementationNodesUseTheirOwnReadScopesAndReviewReceivesBothHandoffs() {
    String workspace = workspaces.createIdeaWorkspace(FILES).id();
    var view = ready(workspace, "制作可单步演示的冒泡排序网页", "draft-run-0004");
    var confirmed = candidates.confirm(view.id(), confirm(view));
    waitTerminal(confirmed.runId());

    var nodes = plans.find(confirmed.runId());
    var specs = plans.specsByNode(confirmed.runId());
    var byAgent =
        nodes.stream()
            .filter(node -> node.agentId() != null)
            .collect(Collectors.toMap(PlanNode::agentId, node -> node));

    for (var tool : records.tools(confirmed.runId())) {
      String agentId = String.valueOf(tool.get("AGENT_ID"));
      var node = byAgent.get(UUID.fromString(agentId));
      assertNotNull(node, "工具调用必须归属于计划节点");
      var spec = specs.get(node.id());
      Object version = tool.get("RESOURCE_VERSION");
      if (version != null) {
        var paths =
            dev.workbench.framework.tool.ResourceVersion.decode(version.toString()).keySet();
        assertTrue(spec.readFiles().containsAll(paths), "工具调用超出节点读取范围：" + paths);
      }
    }

    UUID reviewId =
        nodes.stream().filter(node -> "review".equals(node.key())).findFirst().orElseThrow().id();
    var inbound =
        workflowRuns.workflowHandoffs(confirmed.runId()).stream()
            .filter(handoff -> reviewId.equals(handoff.receiverNodeId()))
            .toList();
    assertEquals(2, inbound.size(), "最终审查必须收到两个开发节点的交接");
    var senders =
        inbound.stream().map(handoff -> handoff.senderNodeId()).collect(Collectors.toSet());
    assertEquals(
        nodes.stream()
            .filter(node -> "engine".equals(node.key()) || "ui".equals(node.key()))
            .map(PlanNode::id)
            .collect(Collectors.toSet()),
        senders);
    var implementAgents =
        nodes.stream()
            .filter(node -> node.workspaceWrite())
            .map(PlanNode::agentId)
            .collect(Collectors.toSet());
    assertEquals(2, implementAgents.size(), "两个开发节点必须装配独立 Agent 实例");

    PlanNodeSpec review = specs.get(reviewId);
    assertTrue(review.readFiles().isEmpty() && review.writeFiles().isEmpty());
    assertTrue(
        review.outputs().stream()
            .anyMatch(output -> output.required() && output.kind() == PlanNodeSpec.Kind.TEXT));
  }

  @Test
  void inputVersionChangeRejectsConfirmation() {
    String workspace = workspaces.createIdeaWorkspace(FILES).id();
    var view = ready(workspace, "制作可单步演示的冒泡排序网页", "draft-run-0005");
    var wrong =
        new PlanCandidateService.ConfirmRequest(
            view.candidateSha256(), view.inputSha256(), view.inputVersion() + 1, "mock", null);
    assertThrows(IllegalStateException.class, () -> candidates.confirm(view.id(), wrong));
  }

  @Test
  void interruptedPreparationAfterGitIsReconciledByOperationId() {
    int before = workspaces.list().size();
    var view = ready(independentInput("制作可单步演示的冒泡排序网页"), "draft-p14-0001");
    String operationId = UUID.randomUUID().toString();
    String preparationId = UUID.randomUUID().toString();
    // 模拟中断遗留：准备开始时间早于租约，允许更换租约持有者。
    tx.executeWithoutResult(
        status ->
            candidateStore.beginPreparation(
                view.id(),
                "LOCAL",
                "READY",
                "PREPARING",
                operationId,
                preparationId,
                Instant.now().minusSeconds(7200).toString()));
    var created = workspaces.createIdeaWorkspace(FILES, operationId);
    assertEquals(
        "PREPARING", candidateStore.findById(view.id()).orElseThrow().getPreparationStatus());

    // 模拟 Git 已完成、候选状态未保存：确认时按稳定操作标识收口并复用同一工作区。
    var confirmed = candidates.confirm(view.id(), confirm(view));
    assertEquals(before + 1, workspaces.list().size(), "收口不能创建第二个工作区");
    assertEquals(created.id(), records.taskDefinition(confirmed.taskId()).workspaceId());
    assertEquals("CONSUMED", candidates.get(view.id()).status());
    assertEquals(
        operationId,
        candidateStore.findById(view.id()).orElseThrow().getWorkspaceOperationId(),
        "接管前后必须使用稳定的工作区操作标识");
  }

  @Test
  void leaseExpiryTakeoverKeepsSingleWorkspaceRunAndDispatch() throws Exception {
    int beforeWorkspaces = workspaces.list().size();
    int beforeTasks = records.tasks().size();
    int beforeDispatches = runs.dispatchInvocationCount;
    var view = ready(independentInput("制作可单步演示的冒泡排序网页"), "draft-p14-lease");
    var entered = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    var firstCall = new java.util.concurrent.atomic.AtomicBoolean(true);
    // 第一个请求完成目录登记后暂停在结算之前；租约随后过期。
    org.mockito.Mockito.doAnswer(
            invocation -> {
              Object result = invocation.callRealMethod();
              if (firstCall.compareAndSet(true, false)) {
                entered.countDown();
                release.await(30, java.util.concurrent.TimeUnit.SECONDS);
              }
              return result;
            })
        .when(workspaces)
        .createIdeaWorkspace(
            org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.anyString());
    var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
    try {
      var first = pool.submit(() -> candidates.confirm(view.id(), confirm(view)));
      assertTrue(entered.await(10, java.util.concurrent.TimeUnit.SECONDS));
      String workspaceOperationId =
          candidateStore.findById(view.id()).orElseThrow().getWorkspaceOperationId();
      assertNotNull(workspaceOperationId);
      // 租约持有者仍停在旧请求，但租约已过期，允许接管。
      jdbc.update(
          "UPDATE plan_candidates SET preparation_started_at=? WHERE id=?",
          Instant.now().minusSeconds(7200).toString(),
          view.id());
      var second = pool.submit(() -> candidates.confirm(view.id(), confirm(view)));
      var secondResult = second.get(30, java.util.concurrent.TimeUnit.SECONDS);
      release.countDown();
      var firstFailure =
          assertThrows(
              java.util.concurrent.ExecutionException.class,
              () -> first.get(30, java.util.concurrent.TimeUnit.SECONDS));
      assertTrue(
          String.valueOf(firstFailure.getCause().getMessage()).contains("接管"),
          String.valueOf(firstFailure.getCause().getMessage()));

      assertEquals(beforeWorkspaces + 1, workspaces.list().size(), "只能创建一个工作区");
      assertEquals(beforeTasks + 1, records.tasks().size(), "只能创建一个任务");
      assertEquals(beforeDispatches + 1, runs.dispatchInvocationCount, "只能派发一次");
      assertEquals("CONSUMED", candidates.get(view.id()).status());
      assertNotNull(records.run(secondResult.runId()));
      assertEquals(
          workspaceOperationId,
          candidateStore.findById(view.id()).orElseThrow().getWorkspaceOperationId(),
          "接管后仓库目录标识保持不变");
    } finally {
      release.countDown();
      pool.shutdownNow();
    }
  }

  @Test
  void concurrentConfirmationWaitsForActivePreparationAndCreatesOneRun() throws Exception {
    int beforeWorkspaces = workspaces.list().size();
    int beforeTasks = records.tasks().size();
    int beforeDispatches = runs.dispatchInvocationCount;
    var view = ready(independentInput("制作可单步演示的冒泡排序网页"), "draft-p14-concurrent");
    var entered = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    org.mockito.Mockito.doAnswer(
            invocation -> {
              calls.incrementAndGet();
              entered.countDown();
              release.await(20, java.util.concurrent.TimeUnit.SECONDS);
              return invocation.callRealMethod();
            })
        .when(workspaces)
        .createIdeaWorkspace(
            org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.anyString());
    var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
    try {
      var first = pool.submit(() -> candidates.confirm(view.id(), confirm(view)));
      assertTrue(entered.await(10, java.util.concurrent.TimeUnit.SECONDS));
      // 第一个请求已写入 PREPARING，但停在创建目录之前。
      String preparationId = candidateStore.findById(view.id()).orElseThrow().getPreparationId();
      var second = pool.submit(() -> candidates.confirm(view.id(), confirm(view)));
      Thread.sleep(400);
      assertEquals(
          preparationId,
          candidateStore.findById(view.id()).orElseThrow().getPreparationId(),
          "活跃准备不能被第二个确认接管");
      assertNull(candidateStore.findById(view.id()).orElseThrow().getPreparedWorkspaceId());
      assertEquals(1, calls.get(), "第二个确认不能自行创建工作区");

      release.countDown();
      var firstResult = first.get(30, java.util.concurrent.TimeUnit.SECONDS);
      var secondResult = second.get(30, java.util.concurrent.TimeUnit.SECONDS);
      assertEquals(firstResult.runId(), secondResult.runId(), "两个确认必须指向同一运行");
      assertEquals(beforeWorkspaces + 1, workspaces.list().size(), "只能创建一个工作区");
      assertEquals(beforeTasks + 1, records.tasks().size(), "只能创建一个任务");
      assertEquals(beforeDispatches + 1, runs.dispatchInvocationCount, "只能派发一次");
      assertEquals(1, calls.get());
    } finally {
      release.countDown();
      pool.shutdownNow();
    }
  }

  @Test
  void runCreationFailureAfterPreparationReusesWorkspaceOnRetry() {
    int before = workspaces.list().size();
    var view = ready(independentInput("制作可单步演示的冒泡排序网页"), "draft-p14-0002");
    org.mockito.Mockito.doThrow(new IllegalStateException("模拟运行入库失败"))
        .doCallRealMethod()
        .when(taskCreations)
        .persistFromCandidate(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any());
    assertThrows(IllegalStateException.class, () -> candidates.confirm(view.id(), confirm(view)));
    assertEquals(before + 1, workspaces.list().size(), "准备阶段只创建一个工作区");
    assertEquals("READY", candidates.get(view.id()).status(), "创建失败不能消费候选");
    String prepared = candidateStore.findById(view.id()).orElseThrow().getPreparedWorkspaceId();
    assertNotNull(prepared);

    var confirmed = candidates.confirm(view.id(), confirm(view));
    assertEquals(before + 1, workspaces.list().size(), "重试不能创建第二个工作区");
    assertEquals(prepared, records.taskDefinition(confirmed.taskId()).workspaceId());
  }

  private PlanCandidateService.CandidateView ready(
      String workspaceId, String goal, String draftKey) {
    return ready(input(workspaceId, goal), draftKey);
  }

  private PlanCandidateService.CandidateView ready(PlanCandidateInput input, String draftKey) {
    var created =
        candidates.generate(
            new PlanCandidateService.GenerateRequest(
                "req-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16),
                input,
                null,
                draftKey));
    long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
    var view = candidates.get(created.id());
    while (view.status().equals("GENERATING")) {
      if (System.nanoTime() > deadline) fail("候选未结算：" + view.status());
      try {
        Thread.sleep(25);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        fail("等待被中断");
      }
      view = candidates.get(created.id());
    }
    assertEquals("READY", view.status(), String.valueOf(view.errors()));
    return view;
  }

  private PlanCandidateService.ConfirmRequest confirm(PlanCandidateService.CandidateView view) {
    return new PlanCandidateService.ConfirmRequest(
        view.candidateSha256(), view.inputSha256(), view.inputVersion(), "mock", null);
  }

  private PlanCandidateInput independentInput(String goal) {
    return new PlanCandidateInput(
        goal,
        TaskEntryKind.CODE,
        null,
        true,
        FILES,
        List.of("使用原生 JavaScript，不引入构建工具。"),
        List.of("输入 5,1,4,2,8，单步执行后显示升序结果 1,2,4,5,8。"),
        "冒泡排序网页");
  }

  private PlanCandidateInput input(String workspaceId, String goal) {
    return new PlanCandidateInput(
        goal,
        TaskEntryKind.CODE,
        workspaceId,
        true,
        FILES,
        List.of("使用原生 JavaScript，不引入构建工具。"),
        List.of("输入 5,1,4,2,8，单步执行后显示升序结果 1,2,4,5,8。"),
        "冒泡排序网页");
  }

  private void waitTerminal(String runId) {
    long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
    while (records.runSnapshot(runId).status().canCancel()) {
      if (System.nanoTime() > deadline) fail("运行未结束：" + records.runSnapshot(runId).status());
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        fail("等待被中断");
      }
    }
    assertNotEquals(RunStatus.FAILED, records.runSnapshot(runId).status());
  }

  private static final String PLAN =
      """
      {"schemaVersion":1,"nodes":[
        {"key":"spec","title":"约定排序接口","role":"ANALYZE","objective":"核对现有排序数据的格式与边界。",
         "dependsOn":[],"readFiles":["sort.js"],"writeFiles":[],
         "expectedOutputs":[{"kind":"TEXT","path":null,"required":true}],
         "acceptanceCriteria":["列出输入、单步和结果的数据结构。"]},
        {"key":"engine","title":"实现排序逻辑","role":"IMPLEMENT","objective":"生成逐轮比较与交换的结果。",
         "dependsOn":["spec"],"readFiles":["sort.js"],"writeFiles":["sort.js"],
         "expectedOutputs":[{"kind":"FILE","path":"sort.js","required":true}],
         "acceptanceCriteria":["sort.js 导出可调用的排序步骤函数。"]},
        {"key":"ui","title":"接入页面","role":"IMPLEMENT","objective":"接入输入区、柱状图和播放控制。",
         "dependsOn":["spec"],"readFiles":["index.html","style.css"],"writeFiles":["index.html","style.css"],
         "expectedOutputs":[{"kind":"FILE","path":"index.html","required":true},{"kind":"FILE","path":"style.css","required":true}],
         "acceptanceCriteria":["页面可输入数组并显示柱状条。"]},
        {"key":"review","title":"汇合审查","role":"REVIEW","objective":"核对两个开发节点的产出和证据。",
         "dependsOn":["engine","ui"],"readFiles":[],"writeFiles":[],
         "expectedOutputs":[{"kind":"TEXT","path":null,"required":true}],
         "acceptanceCriteria":["列出未验证项。"]}],
       "assumptions":[],"openQuestions":[]}
      """;
}
