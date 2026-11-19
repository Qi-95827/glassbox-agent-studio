package dev.workbench.task;

import static org.junit.jupiter.api.Assertions.*;

import dev.workbench.app.WorkbenchApplication;
import dev.workbench.domain.RunStatus;
import dev.workbench.domain.StopCause;
import dev.workbench.domain.TaskEntryKind;
import dev.workbench.framework.workflow.plan.PlanService;
import dev.workbench.orchestration.CreateTaskRequest;
import dev.workbench.orchestration.RunService;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** 恢复子运行继承父运行价格版本和扣除已用额度后的剩余费用预算。 */
@SpringBootTest(
    classes = WorkbenchApplication.class,
    properties = {
      "spring.datasource.url=jdbc:h2:mem:task8costrecovery;DB_CLOSE_DELAY=-1",
      "workbench.workspace-root=${java.io.tmpdir}/agent-workbench-task8-cost-recovery"
    })
class Task8CostRecoveryIntegrationTest {
  @Autowired RunService runs;
  @Autowired RunRecordService records;
  @Autowired PlanService plans;
  @Autowired WorkflowExecutionService workflowRuns;
  @Autowired WorkflowRecoveryService recovery;
  @Autowired CostService costs;

  @Test
  void recoveredChildInheritsRemainingCostBudgetAndStopsBeforeModelCall() {
    costs.publish("default", "mock", "CNY", 1_000_000, 1_000_000, 100, 100, "test");
    String parent =
        runs.create(
            new CreateTaskRequest(
                "恢复费用预算验证",
                "normal",
                4,
                2,
                30,
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
                TaskEntryKind.REQUIREMENT,
                null,
                null,
                null,
                null,
                null,
                null,
                true,
                null,
                null,
                null,
                100L));
    var plan = plans.workflowPlan(parent);
    workflowRuns.approveWorkflow(parent, plan.id());
    records.update(parent, RunStatus.RUNNING, null, null, 0, 0, 0);
    workflowRuns.checkpointBeforeFirstNode(parent, plan.id(), 0);
    records.finishStoppedRun(
        new StopCompletion(
            UUID.fromString(parent),
            null,
            StopCause.INTERRUPTED,
            "模拟首节点前中断",
            0,
            0,
            0,
            null));
    var checkpoints = recovery.checkpoints(parent, plan);
    var checkpoint = checkpoints.getFirst();
    String child = runs.recover(parent, checkpoint.id().toString());
    waitTerminal(child);
    assertEquals("LIMITED", records.runSnapshot(child).status().name());
    assertEquals(
        StopCause.COST_LIMIT.name(), records.run(child).get("STOP_CAUSE"));
    assertTrue(records.models(child).isEmpty(), "子运行不得在预算不足时发起模型调用");
    var estimate = costs.estimate(child);
    assertNotNull(estimate);
    assertEquals(0L, estimate.get("SETTLED_MICROS"));
    assertEquals(100L, estimate.get("MAX_COST_MICROS"));
  }

  private void waitTerminal(String runId) {
    long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
    while (records.runSnapshot(runId).status().canCancel()) {
      if (System.nanoTime() > deadline) fail("运行未结束：" + runId);
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        fail("等待被中断");
      }
    }
  }
}
