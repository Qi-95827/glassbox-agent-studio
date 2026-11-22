package dev.workbench.orchestration;

import static org.junit.jupiter.api.Assertions.*;

import dev.workbench.app.WorkbenchApplication;
import dev.workbench.domain.TaskEntryKind;
import dev.workbench.framework.planning.PlanCandidateModel;
import dev.workbench.planning.PlanCandidateInput;
import dev.workbench.planning.PlanCandidateService;
import dev.workbench.store.RunRecordService;
import dev.workbench.task.WorkflowExecutionService;
import dev.workbench.workspace.WorkspaceService;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

/** 有界反思环：审查打回一次→自动重做上游→再次审查通过；超限转人工。 */
@SpringBootTest(
    classes = WorkbenchApplication.class,
    properties = {
      "spring.datasource.url=jdbc:h2:mem:reflection_loop;DB_CLOSE_DELAY=-1",
      "workbench.workspace-root=${java.io.tmpdir}/agent-workbench-reflection"
    })
@Import(ReflectionLoopIntegrationTest.StubPlan.class)
class ReflectionLoopIntegrationTest {
  private static final String PLAN =
      """
      {"schemaVersion":1,"nodes":[
        {"key":"inspect","title":"反思分析","role":"ANALYZE","objective":"分析授权文件并给出结论。",
         "dependsOn":[],"readFiles":["a.js"],"writeFiles":[],
         "expectedOutputs":[{"kind":"TEXT","path":null,"required":true}],
         "acceptanceCriteria":["给出可核对结论"]},
        {"key":"review","title":"反思审查","role":"REVIEW","objective":"审查上游结论。",
         "dependsOn":["inspect"],"readFiles":[],"writeFiles":[],
         "expectedOutputs":[{"kind":"TEXT","path":null,"required":true}],
         "acceptanceCriteria":["给出审查结论"]}],
       "revisionEdges":[{"fromReviewerNodeKey":"review","toTargetNodeKey":"inspect","maxRevisions":1}],
       "assumptions":[],"openQuestions":[]}
      """;

  @TestConfiguration
  static class StubPlan {
    @Bean
    @Primary
    PlanCandidateModel reflectionPlanModel() {
      return (instructions, input) -> new PlanCandidateModel.Reply(PLAN, "reflection-plan", 10, 20);
    }
  }

  @Autowired PlanCandidateService candidates;
  @Autowired WorkspaceService workspaces;
  @Autowired RunRecordService records;
  @Autowired WorkflowExecutionService workflowRuns;

  @Test
  void reviewerRejectionTriggersOneBoundedRevisionAndThenPasses() {
    String workspaceId = workspaces.createIdeaWorkspace(List.of("a.js")).id();
    var view = ready(workspaceId);
    var confirmed = candidates.confirm(view.id(), new PlanCandidateService.ConfirmRequest(
        view.candidateSha256(), view.inputSha256(), view.inputVersion(), "mock", null));
    waitTerminal(confirmed.runId());

    var run = records.run(confirmed.runId());
    assertEquals("COMPLETED", run.get("STATUS"), String.valueOf(run.get("REASON")));
    var attempts = workflowRuns.workflowAttempts(confirmed.runId());
    assertEquals(4, attempts.size(), "分析、审查各两次尝试");
    assertTrue(
        records.events(confirmed.runId()).stream()
            .anyMatch(event -> "REVISION_TRIGGERED".equals(event.get("EVENT_TYPE"))));
  }

  private PlanCandidateService.CandidateView ready(String workspaceId) {
    var created =
        candidates.generate(
            new PlanCandidateService.GenerateRequest(
                "req-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16),
                new PlanCandidateInput(
                    "反思教学：审查打回后自动修订",
                    TaskEntryKind.CODE,
                    workspaceId,
                    false,
                    List.of("a.js"),
                    List.of(),
                    List.of("给出可核对结论"),
                    null,
                    "BUILTIN",
                    null,
                    null,
                    List.of(),
                    true),
                null,
                "draft-reflection-0001"));
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

  private void waitTerminal(String runId) {
    long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
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
}
