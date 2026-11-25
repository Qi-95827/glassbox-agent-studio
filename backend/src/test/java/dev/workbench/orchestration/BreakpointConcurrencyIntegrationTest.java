package dev.workbench.orchestration;

import static org.junit.jupiter.api.Assertions.*;

import dev.workbench.app.WorkbenchApplication;
import dev.workbench.domain.RunStatus;
import dev.workbench.domain.TaskEntryKind;
import dev.workbench.planning.PlanService;
import dev.workbench.store.GlassboxTraceStore;
import dev.workbench.store.RunStateStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** 验证多断点挂起不饥饿 4 线程运行池，且支持立即取消与继续完成后续节点。 */
@SpringBootTest(
    classes = WorkbenchApplication.class,
    properties = {
      "spring.datasource.url=jdbc:h2:mem:breakpoint_concurrency;DB_CLOSE_DELAY=-1",
      "workbench.workspace-root=${java.io.tmpdir}/agent-workbench-bp-conc"
    })
class BreakpointConcurrencyIntegrationTest {
  @Autowired RunService runs;
  @Autowired PlanService plans;
  @Autowired GlassboxTraceStore traces;
  @Autowired RunStateStore states;

  @Test
  void fivePausedBreakpointRunsDoNotStarvePoolAndSupportCancelAndResume() throws Exception {
    List<String> pausedRunIds = new ArrayList<>();
    List<String> allRunIds = new ArrayList<>();
    try {
      for (int i = 0; i < 5; i++) {
        String runId = runs.create(request("并发断点测试-" + i + "-" + UUID.randomUUID()));
        pausedRunIds.add(runId);
        allRunIds.add(runId);
        var plan = plans.workflowPlan(runId);
        assertNotNull(plan);
        String firstReadyNodeId =
            plans.readyNodes(plans.checkedWorkflowNodes(runId, plan)).getFirst().id().toString();
        traces.updatePlanControl(
            runId,
            GlassboxTraceStore.MODE_STEP_TEACHING,
            new GlassboxTraceStore.CollectionOrNull(List.of(firstReadyNodeId)));
        runs.approvePlan(runId, plan.id());
      }

      // 默认 workflowRunTaskExecutor 仅有 4 个核心线程：5 个同时进入 PAUSED 直接证明断点已让出线程。
      for (String runId : pausedRunIds) {
        awaitStatus(runId, RunStatus.PAUSED, Duration.ofSeconds(20));
      }

      // 在 5 个断点运行保持 PAUSED 期间，第 6 个无断点运行必须在 4 线程池上正常完成，不能发生饥饿。
      String normalRunId = runs.create(request("第6个无断点运行-" + UUID.randomUUID()));
      allRunIds.add(normalRunId);
      runs.approvePlan(normalRunId, plans.workflowPlan(normalRunId).id());
      awaitStatus(normalRunId, RunStatus.COMPLETED, Duration.ofSeconds(20));

      // 对处于断点让出态的 PAUSED 运行执行 /cancel，必须立即落入 CANCELLED 终态。
      String cancelledRunId = pausedRunIds.get(0);
      runs.cancel(cancelledRunId);
      assertEquals(RunStatus.CANCELLED, states.runSnapshot(cancelledRunId).status());

      // 对处于断点让出态的 PAUSED 运行执行 /resume，必须越过已放行断点并推进至 COMPLETED。
      String resumedRunId = pausedRunIds.get(1);
      runs.resume(resumedRunId);
      awaitStatus(resumedRunId, RunStatus.COMPLETED, Duration.ofSeconds(20));
    } finally {
      for (String runId : allRunIds) {
        try {
          if (states.runSnapshot(runId).status().canCancel()) {
            runs.cancel(runId);
          }
        } catch (RuntimeException ignored) {
          // 运行可能已在测试断言期间自然终结或取消。
        }
      }
    }
  }

  private void awaitStatus(String runId, RunStatus expected, Duration timeout)
      throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    RunStatus latest = states.runSnapshot(runId).status();
    while (latest != expected) {
      if (System.nanoTime() >= deadline) {
        fail("等待运行 " + runId + " 达到状态 " + expected + " 超时，当前状态：" + latest);
      }
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
        "dual-analysis",
        true,
        null);
  }
}
