package dev.workbench.orchestration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.workbench.app.WorkbenchApplication;
import dev.workbench.domain.EventType;
import dev.workbench.domain.ModelMode;
import dev.workbench.domain.RunLimits;
import dev.workbench.domain.RunStatus;
import dev.workbench.domain.Scenario;
import dev.workbench.domain.TaskDefinition;
import dev.workbench.domain.TaskEntryKind;
import dev.workbench.delivery.ExecutionViewService;
import dev.workbench.report.HtmlReportService;
import dev.workbench.store.RunRecordService;
import dev.workbench.store.RunStateStore;
import dev.workbench.timeline.RunObservationService;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 收口包 G-2：历史无 {@code WorkflowPlan} 的旧库运行必须保持只读兼容。
 *
 * <p>旧库中存在遗留直跑轨写入的运行（无工作流计划与计划头）。它们不能被再次执行，也不能让查询、诊断、
 * 时间轴与报告接口出现 500/NPE；恢复请求必须给出明确的「遗留直跑恢复已下线」说明。
 */
@SpringBootTest(
    classes = WorkbenchApplication.class,
    properties = {
      "spring.datasource.url=jdbc:h2:mem:legacy_run_readonly;DB_CLOSE_DELAY=-1",
      "workbench.workspace-root=${java.io.tmpdir}/agent-workbench-legacy-readonly"
    })
class LegacyRunReadOnlyCompatibilityTest {
  @Autowired RunService runs;
  @Autowired RunRecordService records;
  @Autowired RunStateStore states;
  @Autowired ExecutionViewService executionViews;
  @Autowired RunObservationService observations;
  @Autowired HtmlReportService reports;
  @Autowired org.springframework.jdbc.core.JdbcTemplate db;

  @Test
  void finishedLegacyRunRemainsReadableThroughEveryReadOnlyView() {
    String runId = legacyRun(RunStatus.COMPLETED, "旧形态分析结果", null);
    assertDoesNotThrow(() -> records.run(runId));
    var view = assertDoesNotThrow(() -> executionViews.view(runId));
    assertNotNull(view);
    assertEquals("COMPLETED", view.runStatus());
    assertEquals(
        0,
        db.queryForObject(
            "SELECT COUNT(*) FROM workflow_plans WHERE run_id = ?", Integer.class, runId),
        "历史运行没有工作流计划头");
    assertNotNull(view.nodes(), "历史运行的节点视图必须返回列表而不是 null");
    // 旧形态运行仍有固定计划节点（ANALYST → REVIEWER），但绝没有工作流计划头：只读视图必须照常渲染。
    assertEquals(2, view.nodes().size(), "历史运行的固定计划节点必须可读");
    assertDoesNotThrow(() -> observations.timeline(runId));
    assertDoesNotThrow(() -> observations.checkpoints(runId));
    assertDoesNotThrow(() -> reports.render(runId));
    assertDoesNotThrow(() -> reports.renderSummary(runId));
  }

  @Test
  void recoverOfUnplannedInterruptedRunIsRejectedWithClearMessage() {
    String runId = legacyRun(RunStatus.INTERRUPTED, null, "模拟进程中断");
    var error =
        assertThrows(
            IllegalStateException.class,
            () -> runs.recover(runId, UUID.randomUUID().toString()));
    assertTrue(error.getMessage().contains("遗留"), error.getMessage());
    assertTrue(error.getMessage().contains("重新创建任务"), error.getMessage());
  }

  @Test
  void legacyPendingRunWithoutPlanIsSettledAsUnclassifiedFailure() {
    String runId = legacyRun(RunStatus.PENDING, null, null);
    runs.dispatchContinuation(runId);
    Map<String, Object> run = awaitTerminal(runId);
    assertEquals("FAILED", run.get("STATUS"), String.valueOf(run.get("REASON")));
    assertEquals("UNCLASSIFIED_FAILURE", run.get("STOP_CAUSE"));
    assertTrue(run.get("REASON").toString().contains("遗留"), String.valueOf(run.get("REASON")));
  }

  /** 直接写入一条旧形态运行：有任务与运行，但没有任何工作流计划头。 */
  private String legacyRun(RunStatus status, String result, String reason) {
    String taskId = UUID.randomUUID().toString();
    String runId = UUID.randomUUID().toString();
    TaskDefinition task =
        new TaskDefinition(
            UUID.fromString(taskId),
            "历史遗留任务",
            Scenario.NORMAL,
            ModelMode.MOCK,
            null,
            null,
            false,
            null,
            false,
            List.of(),
            List.of(),
            TaskEntryKind.GENERAL);
    records.createTaskAndFirstRun(
        task,
        UUID.fromString(runId),
        new RunLimits(4, 2, 2, null, null, 30, null, null),
        "历史遗留运行");
    if (status != RunStatus.PENDING)
      records.update(runId, status, reason, result, 1, 0, 10);
    records.event(runId, EventType.RUN_STARTED, "历史运行开始。");
    return runId;
  }

  private Map<String, Object> awaitTerminal(String runId) {
    long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
    while (true) {
      var run = records.run(runId);
      if (!RunStatus.valueOf(String.valueOf(run.get("STATUS"))).canCancel()) return run;
      if (System.nanoTime() >= deadline) throw new AssertionError("运行未结束：" + runId);
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new AssertionError("等待被中断");
      }
    }
  }
}
