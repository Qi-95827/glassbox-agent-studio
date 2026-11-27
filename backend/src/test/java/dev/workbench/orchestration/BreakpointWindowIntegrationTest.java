package dev.workbench.orchestration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import dev.workbench.app.WorkbenchApplication;
import dev.workbench.domain.ModelMode;
import dev.workbench.domain.RunStatus;
import dev.workbench.domain.TaskEntryKind;
import dev.workbench.framework.agent.definition.AgentDefinitionRef;
import dev.workbench.framework.agent.execution.AgentExecutionRequest;
import dev.workbench.framework.model.AgentModelProvider;
import dev.workbench.framework.model.AgentModelRegistry;
import dev.workbench.framework.planning.PlanCandidateModel;
import dev.workbench.framework.workflow.check.SystemCheckExecutor;
import dev.workbench.framework.workflow.check.SystemCheckRef;
import dev.workbench.framework.workflow.check.SystemCheckRequest;
import dev.workbench.framework.workflow.check.SystemCheckResult;
import dev.workbench.framework.workflow.plan.WorkflowTemplate;
import dev.workbench.infrastructure.agentscope.AgentScopeModelClient;
import dev.workbench.planning.PlanCandidateInput;
import dev.workbench.planning.PlanCandidateService;
import dev.workbench.planning.PlanService;
import dev.workbench.store.GlassboxTraceStore;
import dev.workbench.store.RunStateStore;
import dev.workbench.task.WorkflowExecutionService;
import dev.workbench.workspace.WorkspaceService;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

/**
 * 断点必须在「认领前」生效，且要覆盖系统检查节点。
 *
 * <p>用例 1 构造真实候选计划：1 个分析节点 + 3 个写集互不相交的并发实现节点 + 审查。分析节点被闸门卡住，
 * 测试在它运行期间给第 3 个写节点设断点，随后放行。调度器在同一批里先认领先验节点（engine、ui），
 * 再检查第 3 个节点：必须停下等前两个排空后 PAUSED，而不是把它一起认领执行。断言前两个节点已有尝试
 * （证明确实处于同一并发窗口）、断点节点 0 次尝试；resume 后断点节点才执行并完成。
 *
 * <p>用例 2 用带系统检查的模板：给 SYSTEM_CHECK 节点设断点，断言检查在认领前暂停（0 条检查记录），
 * resume 后才真正执行——与前端允许给检查节点打断点的 UI 行为一致。
 */
@SpringBootTest(
    classes = WorkbenchApplication.class,
    properties = {
      "spring.datasource.url=jdbc:h2:mem:breakpoint_window;DB_CLOSE_DELAY=-1",
      "workbench.workspace-root=${java.io.tmpdir}/agent-workbench-breakpoint-window",
      "workbench.orchestration.parallel-all-plans=true",
      "workbench.orchestration.max-parallel-nodes=3"
    })
@Import(BreakpointWindowIntegrationTest.ProbeBeans.class)
class BreakpointWindowIntegrationTest {
  private static final List<String> FILES = List.of("sort.js", "index.html", "style.css");
  private static final AgentDefinitionRef ANALYSIS = new AgentDefinitionRef("workspace-analysis", 1);
  private static final AgentDefinitionRef SYNTHESIS =
      new AgentDefinitionRef("workspace-findings-summary", 1);
  private static final SystemCheckRef PROBE_CHECK = new SystemCheckRef("probe-check", 1);
  private static volatile CountDownLatch analysisGate;
  private static volatile CountDownLatch writersOverlapGate;
  private static final java.util.concurrent.atomic.AtomicInteger activeWriters =
      new java.util.concurrent.atomic.AtomicInteger();
  private static final java.util.concurrent.atomic.AtomicInteger maxConcurrentWriters =
      new java.util.concurrent.atomic.AtomicInteger();
  private static volatile String planJson;

  @org.junit.jupiter.api.BeforeEach
  void defaults() {
    planJson = THREE_WRITE_PLAN;
    activeWriters.set(0);
    maxConcurrentWriters.set(0);
    writersOverlapGate = null;
  }

  @Autowired PlanCandidateService candidates;
  @Autowired WorkspaceService workspaces;
  @Autowired RunService runs;
  @Autowired PlanService plans;
  @Autowired GlassboxTraceStore traces;
  @Autowired RunStateStore states;
  @Autowired WorkflowExecutionService workflowRuns;
  @Autowired dev.workbench.store.RunRecordService records;

  @AfterEach
  void reset() {
    analysisGate = null;
    writersOverlapGate = null;
    activeWriters.set(0);
    maxConcurrentWriters.set(0);
    planJson = THREE_WRITE_PLAN;
  }

  @Test
  void breakpointInTheMiddleOfARealParallelWindowWaitsAndThenRuns() throws Exception {
    analysisGate = new CountDownLatch(1);
    writersOverlapGate = new CountDownLatch(2);
    String workspace = workspaces.createIdeaWorkspace(FILES).id();
    var view = ready(candidateInput(workspace));
    var confirmed = candidates.confirm(view.id(), confirm(view));
    String runId = confirmed.runId();
    try {
      var plan = plans.workflowPlan(runId);
      assertNotNull(plan);
      var nodes = plans.find(runId);
      var spec = nodes.stream().filter(node -> "spec".equals(node.key())).findFirst().orElseThrow();
      var css = nodes.stream().filter(node -> "css".equals(node.key())).findFirst().orElseThrow();
      var engine = nodes.stream().filter(node -> "engine".equals(node.key())).findFirst().orElseThrow();
      var ui = nodes.stream().filter(node -> "ui".equals(node.key())).findFirst().orElseThrow();

      awaitAttempt(runId, spec.id(), Duration.ofSeconds(20));
      // 分析节点仍卡在模型闸门上：此时设置第 3 个写节点断点，随后放行，进入真实并发批次。
      traces.updatePlanControl(
          runId,
          GlassboxTraceStore.MODE_STEP_TEACHING,
          new GlassboxTraceStore.CollectionOrNull(List.of(css.id().toString())));
      analysisGate.countDown();

      awaitStatus(runId, RunStatus.PAUSED, Duration.ofSeconds(30));

      assertTrue(
          maxConcurrentWriters.get() >= 2,
          "必须观察到 engine 与 ui 真实并发重叠在途（maxConcurrent=" + maxConcurrentWriters.get() + "）");
      assertTrue(
          attempts(runId, engine.id()) >= 1, "同批先验写节点必须有尝试，才能证明并发窗口真实存在");
      assertTrue(attempts(runId, ui.id()) >= 1, "同批第二个写节点必须有尝试");
      assertEquals(0, attempts(runId, css.id()), "断点节点绝不能被认领执行");
      assertEquals(
          "PENDING",
          plans.checkedWorkflowNodes(runId, plan).stream()
              .filter(node -> css.id().equals(node.id()))
              .findFirst()
              .orElseThrow()
              .status()
              .name());

      runs.resume(runId);
      awaitTerminal(runId, Duration.ofSeconds(40));
      assertTrue(attempts(runId, css.id()) >= 1, "resume 后断点节点必须真正执行");
    } finally {
      analysisGate = null;
      cancelIfLive(runId);
    }
  }

  @Test
  void systemCheckBreakpointPausesBeforeTheCheckIsClaimed() throws Exception {
    String workspace = workspaces.createIdeaWorkspace(FILES).id();
    String runId = runs.create(checkRequest("系统检查断点-" + UUID.randomUUID(), workspace));
    try {
      var plan = plans.workflowPlan(runId);
      var probe =
          plans.find(runId).stream()
              .filter(node -> "probe".equals(node.key()))
              .findFirst()
              .orElseThrow();
      traces.updatePlanControl(
          runId,
          GlassboxTraceStore.MODE_STEP_TEACHING,
          new GlassboxTraceStore.CollectionOrNull(List.of(probe.id().toString())));
      runs.approvePlan(runId, plan.id());

      awaitStatus(runId, RunStatus.PAUSED, Duration.ofSeconds(25));
      assertTrue(
          workflowRuns.workflowChecks(runId).stream()
              .noneMatch(check -> probe.id().equals(check.nodeId())),
          "断点生效时系统检查不得被认领执行");

      runs.resume(runId);
      awaitTerminal(runId, Duration.ofSeconds(25));
      assertTrue(
          workflowRuns.workflowChecks(runId).stream()
              .anyMatch(check -> probe.id().equals(check.nodeId())),
          "resume 后系统检查必须执行");
    } finally {
      cancelIfLive(runId);
    }
  }

  private long attempts(String runId, UUID nodeId) {
    return workflowRuns.workflowAttempts(runId).stream()
        .filter(attempt -> nodeId.equals(attempt.nodeId()))
        .count();
  }

  private void awaitAttempt(String runId, UUID nodeId, Duration timeout) throws Exception {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (attempts(runId, nodeId) == 0) {
      if (System.nanoTime() > deadline) fail("等待节点尝试超时：" + nodeId);
      Thread.sleep(25);
    }
  }

  private void awaitTerminal(String runId, Duration timeout) throws Exception {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (states.runSnapshot(runId).status().canCancel()) {
      if (System.nanoTime() > deadline)
        fail("等待运行结束超时，当前：" + states.runSnapshot(runId).status());
      Thread.sleep(30);
    }
  }

  private void awaitStatus(String runId, RunStatus expected, Duration timeout) throws Exception {
    long deadline = System.nanoTime() + timeout.toNanos();
    RunStatus latest = states.runSnapshot(runId).status();
    while (latest != expected) {
      if (System.nanoTime() > deadline)
        fail(
            "等待状态 "
                + expected
                + " 超时，当前："
                + latest
                + "；最近事件："
                + records.events(runId).stream().skip(Math.max(0, records.events(runId).size() - 6)).toList());
      Thread.sleep(30);
      latest = states.runSnapshot(runId).status();
    }
  }

  private void cancelIfLive(String runId) {
    try {
      if (states.runSnapshot(runId).status().canCancel()) runs.cancel(runId);
    } catch (RuntimeException ignored) {
      // 运行可能已在断言期间自然终结。
    }
  }

  private PlanCandidateService.CandidateView ready(PlanCandidateInput input) throws Exception {
    var created =
        candidates.generate(
            new PlanCandidateService.GenerateRequest(
                "req-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16),
                input,
                null,
                "bp-" + UUID.randomUUID().toString().substring(0, 8)));
    long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
    var view = candidates.get(created.id());
    while ("GENERATING".equals(view.status())) {
      if (System.nanoTime() > deadline) fail("候选未结算：" + view.status());
      Thread.sleep(25);
      view = candidates.get(created.id());
    }
    assertEquals("READY", view.status(), String.valueOf(view.errors()));
    return view;
  }

  private static PlanCandidateService.ConfirmRequest confirm(
      PlanCandidateService.CandidateView view) {
    return new PlanCandidateService.ConfirmRequest(
        view.candidateSha256(), view.inputSha256(), view.inputVersion(), "mock", null);
  }

  private static PlanCandidateInput candidateInput(String workspaceId) {
    return new PlanCandidateInput(
        "制作可单步演示的冒泡排序网页",
        TaskEntryKind.CODE,
        workspaceId,
        true,
        FILES,
        List.of("使用原生 JavaScript，不引入构建工具。"),
        List.of("输入 5,1,4,2,8，单步执行后显示升序结果 1,2,4,5,8。"),
        "冒泡排序网页");
  }

  private static CreateTaskRequest checkRequest(String goal, String workspaceId) {
    return new CreateTaskRequest(
        goal,
        "normal",
        10,
        6,
        60,
        "mock",
        workspaceId,
        null,
        false,
        null,
        false,
        null,
        List.of(),
        List.of(),
        null,
        null,
        TaskEntryKind.GENERAL,
        null,
        null,
        FILES,
        null,
        null,
        "check-breakpoint-probe",
        true,
        null);
  }

  @TestConfiguration
  static class ProbeBeans {
    @Bean
    @Primary
    PlanCandidateModel breakpointPlanCandidateModel() {
      return (instructions, input) -> new PlanCandidateModel.Reply(planJson, "bp-plan", 10, 20);
    }

    @Bean
    @Primary
    AgentModelRegistry breakpointAgentModelRegistry() {
      return new AgentModelRegistry(
          List.of(
              new AgentModelProvider() {
                @Override
                public ModelMode mode() {
                  return ModelMode.MOCK;
                }

                @Override
                public dev.workbench.framework.model.ModelClient create(
                    AgentExecutionRequest request) {
                  CountDownLatch gate = analysisGate;
                  if (gate != null && request.role() == dev.workbench.domain.AgentRole.ANALYST) {
                    try {
                      if (!gate.await(20, TimeUnit.SECONDS))
                        throw new IllegalStateException("分析节点闸门等待超时。");
                    } catch (InterruptedException e) {
                      Thread.currentThread().interrupt();
                      throw new IllegalStateException("分析节点被中断。", e);
                    }
                  }
                  CountDownLatch overlap = writersOverlapGate;
                  if (overlap != null
                      && request.role() == dev.workbench.domain.AgentRole.CODER
                      && (request.writeFiles().contains("sort.js")
                          || request.writeFiles().contains("index.html"))) {
                    int now = activeWriters.incrementAndGet();
                    maxConcurrentWriters.accumulateAndGet(now, Math::max);
                    try {
                      overlap.countDown();
                      overlap.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                      Thread.currentThread().interrupt();
                    } finally {
                      activeWriters.decrementAndGet();
                    }
                  }
                  return new AgentScopeModelClient(
                      new ScriptedWriteModel(
                          request.role(),
                          request.fileScopes(),
                          request.writeFiles(),
                          BubbleSortFixture.contents()));
                }
              }));
    }

    @Bean
    WorkflowTemplate checkBreakpointProbe() {
      return new WorkflowTemplate(
          "check-breakpoint-probe",
          1,
          WorkflowTemplate.Scope.WORKSPACE_READ_ONLY,
          2,
          List.of(
              new WorkflowTemplate.Step("prep", "准备上下文", "analysis", ANALYSIS, List.of()),
              WorkflowTemplate.Step.systemCheck(
                  "probe", "探针检查", "verification", PROBE_CHECK, List.of("prep")),
              new WorkflowTemplate.Step(
                  "summary", "汇总", "synthesis", SYNTHESIS, List.of("probe"))));
    }

    @Bean
    SystemCheckExecutor probeCheckExecutor() {
      return new SystemCheckExecutor() {
        @Override
        public SystemCheckRef ref() {
          return PROBE_CHECK;
        }

        @Override
        public SystemCheckResult run(SystemCheckRequest request) {
          return SystemCheckResult.passed("探针检查通过：前置结果已结算。");
        }
      };
    }
  }

  private static final String THREE_WRITE_PLAN =
      """
      {"schemaVersion":1,"nodes":[
        {"key":"spec","title":"约定排序接口","role":"ANALYZE","objective":"核对排序数据格式与边界。",
         "dependsOn":[],"readFiles":["sort.js"],"writeFiles":[],
         "expectedOutputs":[{"kind":"TEXT","path":null,"required":true}],
         "acceptanceCriteria":["列出输入、单步和结果的数据结构。"]},
        {"key":"engine","title":"实现排序逻辑","role":"IMPLEMENT","objective":"生成逐轮比较与交换的结果。",
         "dependsOn":["spec"],"readFiles":["sort.js"],"writeFiles":["sort.js"],
         "expectedOutputs":[{"kind":"FILE","path":"sort.js","required":true}],
         "acceptanceCriteria":["sort.js 导出可调用的排序步骤函数。"]},
        {"key":"ui","title":"接入页面","role":"IMPLEMENT","objective":"接入输入区和播放控制。",
         "dependsOn":["spec"],"readFiles":["index.html"],"writeFiles":["index.html"],
         "expectedOutputs":[{"kind":"FILE","path":"index.html","required":true}],
         "acceptanceCriteria":["页面可输入数组并显示柱状条。"]},
        {"key":"css","title":"样式与布局","role":"IMPLEMENT","objective":"补齐柱状图样式。",
         "dependsOn":["spec"],"readFiles":["style.css"],"writeFiles":["style.css"],
         "expectedOutputs":[{"kind":"FILE","path":"style.css","required":true}],
         "acceptanceCriteria":["柱状条样式与页面布局一致。"]},
        {"key":"review","title":"汇合审查","role":"REVIEW","objective":"核对三个开发节点的产出。",
         "dependsOn":["engine","ui","css"],"readFiles":[],"writeFiles":[],
         "expectedOutputs":[{"kind":"TEXT","path":null,"required":true}],
         "acceptanceCriteria":["列出未验证项。"]}],
       "assumptions":[],"openQuestions":[]}
      """;
}
