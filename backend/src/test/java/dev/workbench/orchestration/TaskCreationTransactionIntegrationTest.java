package dev.workbench.orchestration;

import static org.junit.jupiter.api.Assertions.*;

import dev.workbench.app.WorkbenchApplication;
import dev.workbench.planning.EntryPolicyService;
import dev.workbench.domain.ModelMode;
import dev.workbench.domain.Scenario;
import dev.workbench.domain.TaskDefinition;
import dev.workbench.domain.TaskEntryKind;
import dev.workbench.planning.PlanService;
import dev.workbench.domain.RunLimits;
import dev.workbench.store.RunRecordService;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import dev.workbench.store.RunRecordService;

/**
 * 创建任务第一批持久化验收：任务、首运行、初始计划、入口策略、创建事件在同一事务提交； 失败时一起回滚且不派发，重试恰好创建一套记录。
 */
@SpringBootTest(
    classes = WorkbenchApplication.class,
    properties = {
      "spring.datasource.url=jdbc:h2:mem:taskcreationtx;DB_CLOSE_DELAY=-1",
      "workbench.workspace-root=${java.io.tmpdir}/agent-workbench-taskcreation"
    })
class TaskCreationTransactionIntegrationTest {
  @Autowired RunService runs;
  @Autowired TaskCreationHooks hooks;
  @Autowired TaskCreationService creations;
  @Autowired RunRecordService repository;
  @Autowired PlanService plans;
  @Autowired EntryPolicyService policies;
  @Autowired JdbcTemplate db;

  @Test
  void entryPolicyFailureRollsBackTaskRunPlanEventAndDispatchThenRetryCreatesOneSet() {
    String goal = "入口策略回滚验证-" + java.util.UUID.randomUUID();
    int dispatchedBefore = runs.dispatchInvocationCount;
    hooks.setBeforeEntryPolicySave(
        () -> {
          throw new IllegalStateException("模拟入口策略保存失败。");
        });
    try {
      assertThrows(IllegalStateException.class, () -> runs.create(request(goal, null, false)));
    } finally {
      hooks.reset();
    }

    assertEquals(0, count("SELECT COUNT(*) FROM tasks WHERE goal=?", goal));
    assertEquals(
        0,
        count(
            "SELECT COUNT(*) FROM runs r JOIN tasks t ON t.id=r.task_id WHERE t.goal=?", goal));
    assertEquals(
        0,
        count(
            "SELECT COUNT(*) FROM plan_nodes n JOIN runs r ON r.id=n.run_id"
                + " JOIN tasks t ON t.id=r.task_id WHERE t.goal=?",
            goal));
    assertEquals(
        0,
        count(
            "SELECT COUNT(*) FROM events e JOIN runs r ON r.id=e.run_id"
                + " JOIN tasks t ON t.id=r.task_id WHERE t.goal=?",
            goal));
    assertEquals(
        0,
        count(
            "SELECT COUNT(*) FROM task_entry_policies p JOIN tasks t ON t.id=p.task_id"
                + " WHERE t.goal=?",
            goal));
    assertEquals(
        0,
        count(
            "SELECT COUNT(*) FROM task_role_budgets b JOIN tasks t ON t.id=b.task_id"
                + " WHERE t.goal=?",
            goal));
    assertEquals(
        0,
        count(
            "SELECT COUNT(*) FROM task_tool_budgets_v2 b JOIN tasks t ON t.id=b.task_id"
                + " WHERE t.goal=?",
            goal));
    assertEquals(dispatchedBefore, runs.dispatchInvocationCount);

    String runId = runs.create(request(goal, null, false));

    assertEquals(1, count("SELECT COUNT(*) FROM tasks WHERE goal=?", goal));
    assertEquals(
        1,
        count(
            "SELECT COUNT(*) FROM runs r JOIN tasks t ON t.id=r.task_id WHERE t.goal=?", goal));
    assertTrue(
        count(
                "SELECT COUNT(*) FROM plan_nodes n JOIN runs r ON r.id=n.run_id"
                    + " JOIN tasks t ON t.id=r.task_id WHERE t.goal=?",
                goal)
            > 0);
    assertEquals(
        1,
        count(
            "SELECT COUNT(*) FROM task_entry_policies p JOIN tasks t ON t.id=p.task_id"
                + " WHERE t.goal=?",
            goal));
    assertEquals(
        1,
        count(
            "SELECT COUNT(*) FROM events WHERE run_id=? AND event_type='TASK_CREATED'", runId));
    assertEquals(dispatchedBefore + 1, runs.dispatchInvocationCount);
    assertNotNull(repository.runSnapshot(runId));
  }

  @Test
  void reviewPlanIsPersistedWithEntryPolicyButWaitsForApprovalBeforeDispatch() {
    String goal = "待确认计划验证-" + java.util.UUID.randomUUID();
    int dispatchedBefore = runs.dispatchInvocationCount;

    String runId = runs.create(request(goal, "dual-analysis", true));

    assertEquals(dispatchedBefore, runs.dispatchInvocationCount);
    var plan = plans.workflowPlan(runId);
    assertNotNull(plan);
    assertTrue(plan.reviewRequired());
    assertNull(plan.approvedAt());
    assertEquals("PENDING", repository.run(runId).get("STATUS"));
    String taskId = repository.runSnapshot(runId).taskId().toString();
    assertEquals(
        1,
        count(
            "SELECT COUNT(*) FROM task_entry_policies p JOIN tasks t ON t.id=p.task_id"
                + " WHERE t.goal=? AND t.id=?",
            goal,
            taskId));

    runs.approvePlan(runId, plan.id());

    assertEquals(dispatchedBefore + 1, runs.dispatchInvocationCount);
    assertNotNull(plans.workflowPlan(runId).approvedAt());
  }

  @Test
  void dispatchSeesCommittedTaskAndRun() {
    String goal = "派发前提交验证-" + UUID.randomUUID();
    AtomicInteger tasksVisible = new AtomicInteger(-1);
    AtomicInteger runsVisible = new AtomicInteger(-1);
    runs.beforeDispatch =
        () -> {
          tasksVisible.set(count("SELECT COUNT(*) FROM tasks WHERE goal=?", goal));
          runsVisible.set(
              count(
                  "SELECT COUNT(*) FROM runs r JOIN tasks t ON t.id=r.task_id WHERE t.goal=?",
                  goal));
        };
    try {
      runs.create(request(goal, null, false));
    } finally {
      runs.beforeDispatch = () -> {};
    }
    assertEquals(1, tasksVisible.get(), "派发时任务必须已经提交。");
    assertEquals(1, runsVisible.get(), "派发时运行必须已经提交。");
  }

  @Test
  void knownPrimaryKeysRollBackCleanlyAndDuplicateCreationDoesNotOverwrite() {
    UUID taskId = UUID.randomUUID();
    UUID runId = UUID.randomUUID();
    String goal = "固定主键回滚-" + UUID.randomUUID();
    var task = definition(taskId, goal);
    var limits = new RunLimits(4, 2, 2, null, null, 30, null, null);
    var validation =
        policies.validate(TaskEntryKind.REQUIREMENT, goal, null, false, List.of(), List.of());

    hooks.setBeforeEntryPolicySave(
        () -> {
          throw new IllegalStateException("模拟入口策略保存失败。");
        });
    try {
      assertThrows(
          IllegalStateException.class,
          () -> creations.persist(task, runId, limits, "创建", null, false, validation));
    } finally {
      hooks.reset();
    }
    assertNoRows(taskId, runId);

    creations.persist(task, runId, limits, "创建", null, false, validation);
    assertEquals(1, count("SELECT COUNT(*) FROM tasks WHERE id=?", taskId.toString()));
    assertEquals(
        1, count("SELECT COUNT(*) FROM runs WHERE id=? AND task_id=?", runId.toString(), taskId.toString()));
    assertEquals(1, count("SELECT COUNT(*) FROM events WHERE run_id=?", runId.toString()));
    assertEquals(
        1, count("SELECT COUNT(*) FROM task_entry_policies WHERE task_id=?", taskId.toString()));
    assertTrue(count("SELECT COUNT(*) FROM plan_nodes WHERE run_id=?", runId.toString()) > 0);

    var changed = definition(taskId, goal + "-试图改写");
    var changedValidation =
        policies.validate(TaskEntryKind.REQUIREMENT, changed.goal(), null, false, List.of(), List.of());
    assertThrows(
        RuntimeException.class,
        () -> creations.persist(changed, runId, limits, "重复创建", null, false, changedValidation));
    assertEquals(goal, repository.task(taskId.toString()).get("GOAL"));
    assertEquals(1, count("SELECT COUNT(*) FROM tasks WHERE id=?", taskId.toString()));
    assertEquals(1, count("SELECT COUNT(*) FROM runs WHERE id=?", runId.toString()));
    assertEquals(
        1, count("SELECT COUNT(*) FROM task_entry_policies WHERE task_id=?", taskId.toString()));
  }

  private void assertNoRows(UUID taskId, UUID runId) {
    assertEquals(0, count("SELECT COUNT(*) FROM tasks WHERE id=?", taskId.toString()));
    assertEquals(0, count("SELECT COUNT(*) FROM runs WHERE id=?", runId.toString()));
    assertEquals(0, count("SELECT COUNT(*) FROM plan_nodes WHERE run_id=?", runId.toString()));
    assertEquals(0, count("SELECT COUNT(*) FROM events WHERE run_id=?", runId.toString()));
    assertEquals(
        0, count("SELECT COUNT(*) FROM task_entry_policies WHERE task_id=?", taskId.toString()));
    assertEquals(
        0, count("SELECT COUNT(*) FROM task_role_budgets WHERE task_id=?", taskId.toString()));
    assertEquals(
        0, count("SELECT COUNT(*) FROM task_tool_budgets_v2 WHERE task_id=?", taskId.toString()));
  }

  private TaskDefinition definition(UUID taskId, String goal) {
    return new TaskDefinition(
        taskId,
        goal,
        Scenario.NORMAL,
        ModelMode.MOCK,
        null,
        null,
        false,
        null,
        false,
        List.of(),
        List.of("验收通过"),
        TaskEntryKind.REQUIREMENT,
        List.of());
  }

  private int count(String sql, Object... args) {
    Integer value = db.queryForObject(sql, Integer.class, args);
    return value == null ? 0 : value;
  }

  private CreateTaskRequest request(String goal, String template, boolean reviewPlan) {
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
        TaskEntryKind.REQUIREMENT,
        null,
        null,
        null,
        null,
        null,
        template,
        reviewPlan,
        null);
  }
}
