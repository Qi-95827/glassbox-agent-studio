package dev.workbench.orchestration;

import static org.junit.jupiter.api.Assertions.*;

import dev.workbench.app.WorkbenchApplication;
import dev.workbench.domain.TaskEntryKind;
import dev.workbench.framework.workflow.plan.PlanService;
import dev.workbench.task.RunRecordService;
import dev.workbench.task.WorkflowExecutionService;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** 计划确认使用 JPA 条件更新，并在同一事务内读到新状态。 */
@SpringBootTest(
    classes = WorkbenchApplication.class,
    properties = {
      "spring.datasource.url=jdbc:h2:mem:taskplanapproval;DB_CLOSE_DELAY=-1",
      "workbench.workspace-root=${java.io.tmpdir}/agent-workbench-taskplanapproval"
    })
class TaskPlanApprovalIntegrationTest {
  @Autowired RunService runs;
  @Autowired RunRecordService repository;
  @Autowired PlanService plans;
  @Autowired WorkflowExecutionService workflows;
  @Autowired TransactionTemplate tx;
  @Autowired JdbcTemplate db;

  @Test
  void approvalUpdatesPlanAndSameTransactionReadSeesNewState() {
    String goal = "确认事务读取-" + UUID.randomUUID();
    String runId = runs.create(request(goal));
    var plan = plans.workflowPlan(runId);
    assertNotNull(plan);
    assertNull(plan.approvedAt());
    int dispatchedBefore = runs.dispatchInvocationCount;

    AtomicReference<String> approvedAtInTransaction = new AtomicReference<>();
    tx.executeWithoutResult(
        status -> {
          workflows.approveWorkflow(runId, plan.id());
          approvedAtInTransaction.set(plans.workflowPlan(runId).approvedAt() == null ? null : "SET");
        });

    assertEquals("SET", approvedAtInTransaction.get(), "同一事务内应读到批准时间。");
    assertNotNull(plans.workflowPlan(runId).approvedAt());
    assertNotNull(
        db.queryForObject(
            "SELECT approved_at FROM workflow_plans WHERE run_id=?", String.class, runId));
    assertEquals(
        1,
        db.queryForObject(
            "SELECT COUNT(*) FROM events WHERE run_id=? AND event_type='PLAN_APPROVED'",
            Integer.class,
            runId));
    assertEquals(dispatchedBefore, runs.dispatchInvocationCount);
  }

  @Test
  void listsLatestRunAndReadsTaskArchiveAndVersionInSameTransaction() {
    String runId = runs.create(request("任务查询迁移-" + UUID.randomUUID()));
    String taskId = repository.run(runId).get("TASK_ID").toString();
    assertEquals(runId, listRow(taskId).get("RUN_ID"));
    assertEquals(true, listRow(taskId).get("PLAN_REVIEW_PENDING"));

    // 相同创建时间时仍用运行 ID 排序，只取一条。
    String latestId = "ffffffff-ffff-ffff-ffff-ffffffffffff";
    db.update(
        """
        INSERT INTO runs(id,task_id,status,rounds,tools,max_rounds,max_tools,max_seconds,
            elapsed_ms,created_at,updated_at)
        SELECT ?, task_id, 'COMPLETED', 0, 0, max_rounds, max_tools, max_seconds,
            0, created_at, updated_at FROM runs WHERE id=?
        """,
        latestId,
        runId);

    assertEquals(latestId, listRow(taskId).get("RUN_ID"));
    assertEquals("COMPLETED", listRow(taskId).get("STATUS"));
    assertEquals(false, listRow(taskId).get("PLAN_REVIEW_PENDING"));
    assertEquals(1, repository.tasks().stream().filter(row -> taskId.equals(row.get("ID"))).count());

    String withoutRun = UUID.randomUUID().toString();
    db.update(
        "INSERT INTO tasks(id,goal,scenario,created_at) VALUES(?,?,?,?)",
        withoutRun,
        "历史任务无运行",
        "normal",
        RunRecordService.now());
    assertNull(listRow(withoutRun).get("RUN_ID"));
    assertEquals(false, listRow(withoutRun).get("PLAN_REVIEW_PENDING"));

    tx.executeWithoutResult(status -> {
      assertNull(repository.task(taskId).get("ARCHIVED_AT"));
      repository.setTaskArchived(taskId, true);
      assertNotNull(repository.task(taskId).get("ARCHIVED_AT"));
      assertNotNull(listRow(taskId).get("ARCHIVED_AT"));

      db.update("UPDATE tasks SET version=version+1 WHERE id=?", taskId);
      assertEquals(2, repository.taskVersion(taskId));
      assertEquals(2, repository.task(taskId).get("VERSION"));

      repository.setTaskArchived(taskId, false);
      assertNull(listRow(taskId).get("ARCHIVED_AT"));
    });
    assertNull(repository.task(taskId).get("ARCHIVED_AT"));
    assertEquals(latestId, listRow(taskId).get("RUN_ID"));
  }

  private java.util.Map<String, Object> listRow(String taskId) {
    return repository.tasks().stream().filter(row -> taskId.equals(row.get("ID"))).findFirst().orElseThrow();
  }

  private CreateTaskRequest request(String goal) {
    return new CreateTaskRequest(
        goal,
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
