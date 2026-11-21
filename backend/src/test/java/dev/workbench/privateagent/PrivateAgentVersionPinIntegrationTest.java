package dev.workbench.privateagent;

import static org.junit.jupiter.api.Assertions.*;

import dev.workbench.app.WorkbenchApplication;
import dev.workbench.domain.TaskEntryKind;
import dev.workbench.framework.workflow.plan.PlanNode;
import dev.workbench.planning.PlanService;
import dev.workbench.planning.PlanCandidateInput;
import dev.workbench.framework.planning.PlanCandidateModel;
import dev.workbench.planning.PlanCandidateService;
import dev.workbench.store.RunRecordService;
import dev.workbench.task.WorkflowExecutionService;
import dev.workbench.workspace.WorkspaceService;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import dev.workbench.store.RunRecordService;

/** 规划器等待期间发布新版本，不得把同一 P1 建议从 v1 改成 v2。 */
@SpringBootTest(
    classes = WorkbenchApplication.class,
    properties = {
      "spring.datasource.url=jdbc:h2:mem:private_agent_pin;DB_CLOSE_DELAY=-1",
      "workbench.workspace-root=${java.io.tmpdir}/agent-workbench-private-agent-pin"
    })
@Import(PrivateAgentVersionPinIntegrationTest.BlockingPlan.class)
class PrivateAgentVersionPinIntegrationTest {
  private static final AtomicReference<CountDownLatch> ENTERED =
      new AtomicReference<>(new CountDownLatch(1));
  private static final AtomicReference<CountDownLatch> RELEASE =
      new AtomicReference<>(new CountDownLatch(1));
  private static final AtomicReference<String> SEEN_INPUT = new AtomicReference<>();

  private static final String PLAN =
      """
      {
        "schemaVersion": 1,
        "nodes": [
          {
            "key": "inspect",
            "title": "前端检查",
            "role": "ANALYZE",
            "agentRef": "P1",
            "objective": "检查前端文件。",
            "dependsOn": [],
            "readFiles": ["App.jsx", "notes.md"],
            "writeFiles": [],
            "expectedOutputs": [{"kind":"TEXT","path":null,"required":true}],
            "acceptanceCriteria": ["列出读取范围"]
          },
          {
            "key": "summary",
            "title": "汇总",
            "role": "REVIEW",
            "objective": "汇总检查结论。",
            "dependsOn": ["inspect"],
            "readFiles": [],
            "writeFiles": [],
            "expectedOutputs": [{"kind":"TEXT","path":null,"required":true}],
            "acceptanceCriteria": ["包含交接正文"]
          }
        ],
        "assumptions": [],
        "openQuestions": []
      }
      """;

  @TestConfiguration
  static class BlockingPlan {
    @Bean
    @Primary
    PlanCandidateModel blockingPlanModel() {
      return (instructions, input) -> {
        SEEN_INPUT.set(input);
        ENTERED.get().countDown();
        try {
          RELEASE.get().await(20, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException("规划被中断。");
        }
        return new PlanCandidateModel.Reply(PLAN, "blocking-plan", 10, 10);
      };
    }
  }

  @Autowired PrivateComponentService components;
  @Autowired PlanCandidateService candidates;
  @Autowired PlanService plans;
  @Autowired WorkflowExecutionService workflowRuns;
  @Autowired RunRecordService records;
  @Autowired WorkspaceService workspaces;

  @Test
  void plannerAliasStaysOnVersionSeenAtRequestTime() throws Exception {
    resetLatches();
    var tool =
        components
            .publishTool(
                components
                    .createTool(
                        new PrivateComponentService.ToolRequest(
                            "Pin 搜索", "只读搜索", "SEARCH", List.of(".jsx", ".tsx")))
                    .id())
            .id();
    var agent = publishAgent(tool, "Pin 检查 v1", "第一版职责");
    String workspace = workspaces.createIdeaWorkspace(List.of("App.jsx", "notes.md")).id();

    var created = generate(workspace, agent.id());
    assertTrue(ENTERED.get().await(10, TimeUnit.SECONDS));
    assertTrue(SEEN_INPUT.get().contains("\"version\":1"), SEEN_INPUT.get());
    assertTrue(SEEN_INPUT.get().contains("P1"), SEEN_INPUT.get());

    // 模型仍在等待时发布 v2；同一响应必须继续绑定 v1。
    components.updateAgent(
        agent.id(),
        new PrivateComponentService.AgentRequest(
            "Pin 检查 v2",
            "第二版简介",
            "第二版职责",
            "第二版输出",
            "ANALYST",
            true,
            "mock",
            List.of(),
            List.of(new PrivateComponentService.ComponentRef(tool, null)),
            "INHERIT_TASK"));
    components.publishAgent(agent.id());
    RELEASE.get().countDown();

    var view = wait(created);
    assertEquals("READY", view.status(), view.errors().toString());
    @SuppressWarnings("unchecked")
    var binding = (Map<String, Object>) view.agentBindings().get("inspect");
    assertEquals(1, binding.get("definitionVersion"), binding.toString());
    assertEquals(agent.specSha256(), binding.get("specSha256"));
    assertEquals("RECOMMENDED", binding.get("source"));

    var confirmed = candidates.confirm(view.id(), confirm(view));
    waitTerminal(confirmed.runId());
    List<PlanNode> nodes = plans.find(confirmed.runId());
    assertEquals(1, nodes.getFirst().definitionRef().version());
    assertEquals(agent.specSha256(), nodes.getFirst().definitionSha256());
    assertTrue(workflowRuns.workflowAttempts(confirmed.runId()).size() >= 2);
  }

  @Test
  void archivedAfterPlanningRejectsConfirmation() throws Exception {
    resetLatches();
    var tool =
        components
            .publishTool(
                components
                    .createTool(
                        new PrivateComponentService.ToolRequest(
                            "归档搜索", "只读搜索", "SEARCH", List.of(".jsx", ".tsx")))
                    .id())
            .id();
    var agent = publishAgent(tool, "归档检查", "检查职责");
    String workspace = workspaces.createIdeaWorkspace(List.of("App.jsx", "notes.md")).id();
    var created = generate(workspace, agent.id());
    assertTrue(ENTERED.get().await(10, TimeUnit.SECONDS));
    components.archiveAgent(agent.id());
    RELEASE.get().countDown();
    var view = wait(created);
    assertEquals("INVALID", view.status(), view.errors().toString());
    assertTrue(
        view.errors().stream()
            .anyMatch(reason -> reason.contains("归档") || reason.contains("未发布") || reason.contains("不能")));
    assertThrows(IllegalStateException.class, () -> candidates.confirm(view.id(), confirm(view)));
  }

  private PrivateComponentService.AgentView publishAgent(String toolId, String name, String duty) {
    var created =
        components.createAgent(
            new PrivateComponentService.AgentRequest(
                name,
                "只读检查",
                duty,
                "列出读取文件",
                "ANALYST",
                true,
                "mock",
                List.of(),
                List.of(new PrivateComponentService.ComponentRef(toolId, null)),
                "INHERIT_TASK"));
    return components.publishAgent(created.id());
  }

  private PlanCandidateService.CandidateView generate(String workspace, String agentId) {
    var input =
        new PlanCandidateInput(
            "只读检查前端",
            TaskEntryKind.CODE,
            workspace,
            false,
            List.of("App.jsx", "notes.md"),
            List.of(),
            List.of("列出实际读取文件"),
            null,
            "RECOMMEND",
            null,
            null,
            List.of(agentId),
            true);
    return candidates.generate(
        new PlanCandidateService.GenerateRequest(
            dev.workbench.domain.WorkbenchIds.newId().replace("-", ""), input, null, "pin-" + agentId));
  }

  private PlanCandidateService.CandidateView wait(PlanCandidateService.CandidateView created) {
    long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
    var view = created;
    while ("GENERATING".equals(view.status())) {
      if (System.nanoTime() > deadline) fail("候选未结算");
      try {
        Thread.sleep(30);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        fail("等待被中断");
      }
      view = candidates.get(created.id());
    }
    return view;
  }

  private static void resetLatches() {
    ENTERED.set(new CountDownLatch(1));
    RELEASE.set(new CountDownLatch(1));
    SEEN_INPUT.set(null);
  }

  private static PlanCandidateService.ConfirmRequest confirm(
      PlanCandidateService.CandidateView view) {
    return new PlanCandidateService.ConfirmRequest(
        view.candidateSha256(), view.inputSha256(), view.inputVersion(), "mock", null);
  }

  private void waitTerminal(String runId) {
    long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
    while (records.runSnapshot(runId).status().canCancel()) {
      if (System.nanoTime() > deadline) fail("运行未结束");
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        fail("等待被中断");
      }
    }
  }
}
