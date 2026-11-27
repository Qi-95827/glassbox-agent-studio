package dev.workbench.orchestration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import dev.workbench.app.WorkbenchApplication;
import dev.workbench.domain.RunStatus;
import dev.workbench.domain.TaskEntryKind;
import dev.workbench.framework.agent.definition.AgentDefinitionRef;
import dev.workbench.framework.workflow.plan.WorkflowTemplate;
import dev.workbench.planning.PlanService;
import dev.workbench.store.GlassboxTraceStore;
import dev.workbench.store.RunStateStore;
import dev.workbench.task.WorkflowExecutionService;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * 并行批次中的断点必须真正停住节点。
 *
 * <p>回归场景：首批节点跑完后调度器会一次性认领后续多个就绪节点。旧实现只在「在途为空」时检查断点，
 * 于是同批第 2、3 个节点绕过断点直接执行并产生副作用。这里用三路并行模板复现：给第三个节点设断点，
 * 断言运行停在 PAUSED 且该节点 0 次尝试，而第二个节点确实已在同批执行。
 */
@SpringBootTest(
    classes = WorkbenchApplication.class,
    properties = {
      "spring.datasource.url=jdbc:h2:mem:parallel_breakpoint;DB_CLOSE_DELAY=-1",
      "workbench.workspace-root=${java.io.tmpdir}/agent-workbench-parallel-bp"
    })
@Import(ParallelBreakpointIntegrationTest.ProbeTemplate.class)
class ParallelBreakpointIntegrationTest {
  private static final AgentDefinitionRef ANALYSIS = new AgentDefinitionRef("text-analysis", 1);
  private static final AgentDefinitionRef SYNTHESIS = new AgentDefinitionRef("text-synthesis", 1);

  @Autowired RunService runs;
  @Autowired PlanService plans;
  @Autowired GlassboxTraceStore traces;
  @Autowired RunStateStore states;
  @Autowired WorkflowExecutionService workflows;

  @Test
  void breakpointOnTheThirdParallelNodePausesBeforeClaimingIt() throws Exception {
    String runId = runs.create(request("并行断点回归-" + UUID.randomUUID()));
    try {
      var plan = plans.workflowPlan(runId);
      assertNotNull(plan);
      var nodes = plans.find(runId);
      var probeC = nodes.stream().filter(node -> "probe-c".equals(node.key())).findFirst().orElseThrow();
      var probeB = nodes.stream().filter(node -> "probe-b".equals(node.key())).findFirst().orElseThrow();

      traces.updatePlanControl(
          runId,
          GlassboxTraceStore.MODE_STEP_TEACHING,
          new GlassboxTraceStore.CollectionOrNull(List.of(probeC.id().toString())));
      runs.approvePlan(runId, plan.id());

      awaitStatus(runId, RunStatus.PAUSED, Duration.ofSeconds(25));

      // 断点节点必须是 0 次尝试：旧实现会先认领再执行，这里直接暴露副作用。
      var attempts =
          workflows.workflowAttempts(runId).stream()
              .filter(attempt -> probeC.id().equals(attempt.nodeId()))
              .toList();
      assertTrue(attempts.isEmpty(), "断点节点不得被认领执行：" + attempts);

      // 同批的另一个并行节点确已执行，证明断点判断发生在「已有在途节点」的真实批次里。
      assertFalse(
          workflows.workflowAttempts(runId).stream()
              .noneMatch(attempt -> probeB.id().equals(attempt.nodeId())),
          "同批并行节点应已执行，才能证明测试覆盖了旧缺陷条件");
      assertEquals(
          "PENDING",
          plans.checkedWorkflowNodes(runId, plan).stream()
              .filter(node -> probeC.id().equals(node.id()))
              .findFirst()
              .orElseThrow()
              .status()
              .name());
    } finally {
      try {
        if (states.runSnapshot(runId).status().canCancel()) runs.cancel(runId);
      } catch (RuntimeException ignored) {
        // 运行可能已在断言期间终结。
      }
    }
  }

  private void awaitStatus(String runId, RunStatus expected, Duration timeout)
      throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    RunStatus latest = states.runSnapshot(runId).status();
    while (latest != expected) {
      if (System.nanoTime() >= deadline) fail("等待状态 " + expected + " 超时，当前：" + latest);
      Thread.sleep(50);
      latest = states.runSnapshot(runId).status();
    }
  }

  private static CreateTaskRequest request(String goal) {
    return new CreateTaskRequest(
        goal,
        "normal",
        10,
        6,
        60,
        "mock",
        null,
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
        null,
        null,
        null,
        "parallel-breakpoint-probe",
        true,
        null);
  }

  @TestConfiguration
  static class ProbeTemplate {
    @Bean
    WorkflowTemplate parallelBreakpointProbe() {
      return new WorkflowTemplate(
          "parallel-breakpoint-probe",
          1,
          List.of(
              new WorkflowTemplate.Step("probe-a", "并行分析 A", "analysis", ANALYSIS, List.of()),
              new WorkflowTemplate.Step("probe-b", "并行分析 B", "analysis", ANALYSIS, List.of()),
              new WorkflowTemplate.Step("probe-c", "并行分析 C", "analysis", ANALYSIS, List.of()),
              new WorkflowTemplate.Step(
                  "probe-summary",
                  "汇总",
                  "synthesis",
                  SYNTHESIS,
                  List.of("probe-a", "probe-b", "probe-c"))));
    }
  }
}
