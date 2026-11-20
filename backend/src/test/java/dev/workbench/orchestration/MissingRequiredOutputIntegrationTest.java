package dev.workbench.orchestration;

import static org.junit.jupiter.api.Assertions.*;

import dev.workbench.app.WorkbenchApplication;
import dev.workbench.domain.ModelMode;
import dev.workbench.domain.TaskEntryKind;
import dev.workbench.framework.agent.execution.AgentExecutionRequest;
import dev.workbench.framework.model.AgentModelProvider;
import dev.workbench.framework.model.AgentModelRegistry;
import dev.workbench.planning.PlanCandidateInput;
import dev.workbench.planning.PlanCandidateModel;
import dev.workbench.planning.PlanCandidateService;
import dev.workbench.task.RunDeliveryService;
import dev.workbench.task.RunRecordService;
import dev.workbench.task.WorkflowExecutionService;
import dev.workbench.workspace.WorkspaceService;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

/** P07：开发节点缺少必需文件时，审查不得启动，已写入文件和证据保留。 */
@SpringBootTest(
    classes = WorkbenchApplication.class,
    properties = {
      "spring.datasource.url=jdbc:h2:mem:missing_output;DB_CLOSE_DELAY=-1",
      "workbench.workspace-root=${java.io.tmpdir}/agent-workbench-missing-output"
    })
@Import(MissingRequiredOutputIntegrationTest.ScriptedModels.class)
class MissingRequiredOutputIntegrationTest {
  private static final List<String> FILES = List.of("index.html", "sort.js", "style.css");

  @TestConfiguration
  static class ScriptedModels {
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
                      BubbleSortFixture.contents(),
                      // 页面节点故意不写必需文件，模拟遗漏交付。
                      Set.of("index.html"));
                }
              }));
    }
  }

  @Autowired PlanCandidateService candidates;
  @Autowired WorkspaceService workspaces;
  @Autowired RunRecordService records;
  @Autowired WorkflowExecutionService workflowRuns;
  @Autowired RunDeliveryService deliveries;

  @Test
  void missingRequiredFileBlocksRunBeforeReviewAndKeepsWrittenEvidence() {
    String workspaceId = workspaces.createIdeaWorkspace(FILES).id();
    var view = ready(workspaceId);
    var confirmed = candidates.confirm(view.id(), confirm(view));
    waitTerminal(confirmed.runId());

    var run = records.run(confirmed.runId());
    assertEquals("BLOCKED", run.get("STATUS"));
    assertEquals("OUTPUT_MISSING", run.get("STOP_CAUSE"));
    assertTrue(String.valueOf(run.get("REASON")).contains("缺少必需产出"));
    assertTrue(String.valueOf(run.get("REASON")).contains("index.html"));

    var nodes = workflowRuns.workflowAttempts(confirmed.runId());
    var planNodes = records.agents(confirmed.runId());
    assertEquals(3, planNodes.size(), "审查节点不应创建 Agent 实例");
    assertTrue(
        planNodes.stream().noneMatch(agent -> "REVIEWER".equals(agent.get("ROLE_CODE"))),
        "缺交付时不能启动审查");

    // 已写入的 sort.js 保留，缺失的页面文件不产生成功写入回执。
    assertTrue(workspaces.read(workspaceId, "sort.js").content().contains("bubbleSteps"));
    assertFalse(workspaces.read(workspaceId, "index.html").content().contains("冒泡排序演示"));
    var uiAttempt =
        nodes.stream()
            .filter(attempt -> attempt.stopCause() == dev.workbench.domain.StopCause.OUTPUT_MISSING)
            .findFirst()
            .orElseThrow();
    assertNotNull(uiAttempt);

    var delivery = deliveries.delivery(confirmed.runId());
    assertEquals("BLOCKED", delivery.status());
    var ui =
        delivery.nodes().stream().filter(node -> "ui".equals(node.key())).findFirst().orElseThrow();
    assertTrue(ui.files().isEmpty());
    assertEquals(Set.of("index.html", "style.css"), Set.copyOf(ui.missingRequiredFiles()));
    var review =
        delivery.nodes().stream()
            .filter(node -> "review".equals(node.key()))
            .findFirst()
            .orElseThrow();
    assertNull(review.result());
    assertTrue(review.inbound().isEmpty(), "审查没有启动，不应有接收交接");
    assertTrue(delivery.unverified().stream().anyMatch(text -> text.contains("index.html")));
    var engine =
        delivery.nodes().stream()
            .filter(node -> "engine".equals(node.key()))
            .findFirst()
            .orElseThrow();
    assertEquals(1, engine.files().size());
    assertTrue(engine.files().getFirst().currentMatches());
    assertTrue(planNodes.stream().anyMatch(agent -> "FAILED".equals(agent.get("STATUS"))));
  }

  private PlanCandidateService.CandidateView ready(String workspaceId) {
    var created =
        candidates.generate(
            new PlanCandidateService.GenerateRequest(
                "req-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16),
                input(workspaceId),
                null,
                "draft-missing-0001"));
    long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
    var view = candidates.get(created.id());
    while (view.status().equals("GENERATING")) {
      if (System.nanoTime() > deadline) fail("候选未结算");
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

  private PlanCandidateInput input(String workspaceId) {
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

  private void waitTerminal(String runId) {
    long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
    while (records.runSnapshot(runId).status().canCancel()) {
      if (System.nanoTime() > deadline) fail("运行未结束：" + records.runSnapshot(runId).status());
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        fail("等待被中断");
      }
    }
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
