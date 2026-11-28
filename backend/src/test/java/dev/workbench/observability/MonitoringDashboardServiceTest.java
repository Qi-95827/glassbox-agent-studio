package dev.workbench.observability;

import static org.junit.jupiter.api.Assertions.*;

import dev.workbench.app.WorkbenchApplication;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** 任务 B：看板聚合与五步故障诊断的持久化证据回归。 */
@SpringBootTest(
    classes = WorkbenchApplication.class,
    properties = {
      "spring.datasource.url=jdbc:h2:mem:monitoring-dashboard;DB_CLOSE_DELAY=-1",
      "workbench.workspace-root=${java.io.tmpdir}/agent-workbench-monitoring-dashboard"
    })
class MonitoringDashboardServiceTest {
  @Autowired MonitoringDashboardService dashboard;
  @Autowired RunDiagnosticsService diagnostics;
  @Autowired JdbcTemplate db;

  @BeforeEach
  void cleanEvidence() {
    for (String table :
        new String[] {
          "tool_call_traces",
          "model_call_payloads",
          "tool_invocations",
          "model_invocations",
          "plan_nodes",
          "run_cost_limits",
          "git_actions",
          "events",
          "handoff_receipts",
          "handoffs",
          "agent_assemblies",
          "agent_invocations",
          "runs",
          "tasks",
          "resource_ownership"
        }) {
      db.execute("DELETE FROM " + table);
    }
  }

  @Test
  void emptyDatabaseReturnsZeroedDashboardWithoutTimeSeries() {
    var result = dashboard.dashboard("7d", null, null);

    assertEquals(0, result.summary().totalRuns());
    assertEquals(0.0, result.summary().successRate());
    assertNull(result.summary().avgRunDurationMs());
    assertNull(result.summary().estimatedCost());
    assertTrue(result.timeSeries().isEmpty());
    assertTrue(result.rankings().recentRuns().isEmpty());
    assertTrue(result.distributions().taskStatusBreakdown().isEmpty());
  }

  @Test
  void aggregatesMultiModelDeniesAndRankings() {
    String now = Instant.now().toString();
    task("task-a", "实现单文件番茄钟", now);
    task("task-b", "教学剧本 3：ToolGateway 限额与安检演示", now);
    run("run-a", "task-a", "COMPLETED", 1000, null, now);
    run("run-b", "task-b", "BLOCKED", 2000, "PATH_REJECTED", now);

    node("node-a", "run-a", "ANALYST", "COMPLETED", "agent-a", now, now);
    node(
        "node-b",
        "run-b",
        "CODER",
        "FAILED",
        "agent-b",
        Instant.parse(now).minusSeconds(3).toString(),
        now);

    model("model-a1", "run-a", "agent-a", "deepseek-flash", "COMPLETED", 100, 50, 100, null, now);
    model("model-a2", "run-a", "agent-a", "mock", "COMPLETED", 10, 5, 200, null, now);
    model("model-b1", "run-b", "agent-b", "deepseek-flash", "FAILED", 8, 2, 300, "429 Too Many Requests", now);

    tool("tool-a1", "run-a", "agent-a", "WORKSPACE_READ", "COMPLETED", null, 30, now);
    tool("tool-b1", "run-b", "agent-b", "WORKSPACE_WRITE", "FAILED", "工具输入不符合约束", 20, now);
    trace(
        "tool-b1",
        "run-b",
        """
        [{"gate":"PATH_WHITELIST","label":"路径白名单与读写隔离","status":"DENY","detail":"目标文件不在任务白名单中"},
         {"gate":"ARGUMENT_SCHEMA","label":"参数 Schema 校验","status":"DENY","detail":"前置校验未通过"}]
        """,
        now);

    var result = dashboard.dashboard("7d", null, null);

    assertEquals(2, result.summary().totalRuns());
    assertEquals(1, result.summary().completedRuns());
    assertEquals(1, result.summary().blockedRuns());
    assertEquals(1, result.summary().anomalyRunCount());
    assertEquals(50.0, result.summary().successRate());
    assertEquals(1500, result.summary().avgRunDurationMs());
    assertEquals(3, result.summary().totalModelCalls());
    assertEquals(118, result.summary().totalInputTokens());
    assertEquals(57, result.summary().totalOutputTokens());
    assertEquals(2, result.summary().totalToolCalls());
    assertEquals(1, result.summary().toolDenyCount());
    assertEquals(50.0, result.summary().toolDenyRate());

    assertFalse(result.timeSeries().isEmpty());
    assertTrue(result.timeSeries().stream().mapToLong(point -> point.totalTokens()).sum() >= 175);
    assertEquals(2, result.distributions().modelBreakdown().size());
    assertTrue(
        result.distributions().modelBreakdown().stream()
            .anyMatch(slice -> "deepseek-flash".equals(slice.model()) && slice.failedCalls() == 1));
    assertEquals("PATH_WHITELIST", result.distributions().gatewayDenyBreakdown().getFirst().gate());
    assertEquals(1, result.distributions().gatewayDenyBreakdown().getFirst().count());
    assertEquals(1, result.distributions().toolBreakdown().stream()
        .filter(slice -> "WORKSPACE_WRITE".equals(slice.tool()) && slice.failedCalls() == 1)
        .count());
    assertEquals(2, result.rankings().recentRuns().size());
    assertEquals("node-b", result.rankings().slowestNodes().getFirst().nodeId());
    assertEquals(2, result.rankings().highTokenRuns().size());
    assertEquals("run-a", result.rankings().highTokenRuns().getFirst().runId());

    var mockOnly = dashboard.dashboard("7d", null, "mock");
    assertEquals(1, mockOnly.summary().totalRuns());
    assertEquals("run-a", mockOnly.rankings().recentRuns().getFirst().runId());

    var blockedOnly = dashboard.dashboard("7d", "BLOCKED", null);
    assertEquals(1, blockedOnly.summary().totalRuns());
    assertEquals("run-b", blockedOnly.rankings().recentRuns().getFirst().runId());

    var all = dashboard.dashboard("all", null, null);
    assertEquals(2, all.summary().totalRuns());
    assertFalse(all.timeSeries().isEmpty());

    var lastDay = dashboard.dashboard("24h", null, null);
    assertEquals(2, lastDay.summary().totalRuns());
  }

  @Test
  void taskScopedDashboardConvergesEveryBlockToTheSameFilteredRuns() {
    String now = Instant.now().toString();
    String earlier = Instant.parse(now).minusSeconds(120).toString();
    task("task-a", "实现单文件番茄钟", now);
    task("task-b", "教学剧本 3：ToolGateway 限额与安检演示", now);
    // task-a 两轮迭代 + task-b 一条运行：单任务视图只能看到 task-a 的两轮。
    run("run-a1", "task-a", "COMPLETED", 1000, null, earlier);
    run("run-a2", "task-a", "COMPLETED", 3000, null, now);
    run("run-b1", "task-b", "BLOCKED", 2000, "PATH_REJECTED", now);
    node("node-a1", "run-a1", "ANALYST", "COMPLETED", "agent-a1", earlier, earlier);
    node("node-a2", "run-a2", "CODER", "COMPLETED", "agent-a2", earlier, now);
    node("node-b1", "run-b1", "CODER", "FAILED", "agent-b1", earlier, now);
    model("m-a1", "run-a1", "agent-a1", "mock", "COMPLETED", 10, 5, 100, null, earlier);
    model("m-a2", "run-a2", "agent-a2", "mock", "COMPLETED", 20, 8, 120, null, now);
    model("m-b1", "run-b1", "agent-b1", "deepseek-flash", "FAILED", 8, 2, 300, "429", now);
    tool("t-a2", "run-a2", "agent-a2", "WORKSPACE_WRITE", "COMPLETED", null, 20, now);
    tool("t-b1", "run-b1", "agent-b1", "WORKSPACE_WRITE", "FAILED", "工具输入不符合约束", 20, now);

    var scoped = dashboard.dashboard("7d", null, null, "task-a", null);

    assertEquals("task-a", scoped.selectedTaskId());
    assertEquals("实现单文件番茄钟", scoped.selectedTaskGoal());
    assertEquals(2, scoped.summary().totalRuns());
    assertEquals(2, scoped.summary().completedRuns());
    assertEquals(0, scoped.summary().failedRuns());
    assertEquals(2, scoped.summary().totalModelCalls());
    assertEquals(30, scoped.summary().totalInputTokens());
    assertEquals(1, scoped.summary().totalToolCalls());
    assertEquals(2, scoped.rankings().recentRuns().size());
    assertTrue(
        scoped.rankings().recentRuns().stream().allMatch(run -> "task-a".equals(run.taskId())));
    assertEquals(
        1, scoped.distributions().modelBreakdown().size(), "圆环只应包含该任务的模型");
    assertEquals("mock", scoped.distributions().modelBreakdown().getFirst().model());
    assertEquals(1, scoped.distributions().toolBreakdown().size());
    assertEquals(2, scoped.distributions().taskStatusBreakdown().stream()
        .mapToLong(slice -> slice.count())
        .sum());
    assertTrue(
        scoped.timeSeries().stream().mapToLong(point -> point.runCount()).sum() >= 2,
        "时序图必须与同一份过滤后的运行集合一致");
    // 筛选下拉仍然列出全部可读任务，便于一键切回。
    assertEquals(2, scoped.taskOptions().size());
    assertEquals(null, dashboard.dashboard("7d", null, null).selectedTaskId());
  }

  @Test
  void runIdScopesToExactlyOneRunAndRejectsCrossTaskCombinations() {
    String now = Instant.now().toString();
    task("task-a", "实现单文件番茄钟", now);
    task("task-b", "教学剧本 3：ToolGateway 限额与安检演示", now);
    run("run-a1", "task-a", "COMPLETED", 1000, null, now);
    run("run-a2", "task-a", "COMPLETED", 2000, null, now);
    run("run-b1", "task-b", "BLOCKED", 2000, "PATH_REJECTED", now);

    var one = dashboard.dashboard("all", null, null, "task-a", "run-a2");
    assertEquals(1, one.summary().totalRuns());
    assertEquals("run-a2", one.rankings().recentRuns().getFirst().runId());
    assertEquals("task-a", one.selectedTaskId());

    // 任务与运行不匹配、任务不可读：一律 404，绝不回退全局。
    assertThrows(
        org.springframework.dao.EmptyResultDataAccessException.class,
        () -> dashboard.dashboard("all", null, null, "task-a", "run-b1"));
    assertThrows(
        org.springframework.dao.EmptyResultDataAccessException.class,
        () -> dashboard.dashboard("all", null, null, "task-missing", null));
  }

  @Test
  void taskScopeCombinesWithRangeStatusAndModelFilters() {
    String now = Instant.now().toString();
    String old = Instant.parse(now).minusSeconds(60 * 60 * 24 * 40L).toString();
    task("task-a", "实现单文件番茄钟", now);
    run("run-a1", "task-a", "COMPLETED", 1000, null, now);
    run("run-a2", "task-a", "FAILED", 2000, null, old);
    model("m-a1", "run-a1", "agent-a1", "mock", "COMPLETED", 10, 5, 100, null, now);
    model("m-a2", "run-a2", "agent-a2", "deepseek-flash", "FAILED", 4, 1, 200, "429", old);

    var combined = dashboard.dashboard("7d", "COMPLETED", "mock", "task-a", null);
    assertEquals(1, combined.summary().totalRuns());
    assertEquals("run-a1", combined.rankings().recentRuns().getFirst().runId());

    var allRange = dashboard.dashboard("all", null, null, "task-a", null);
    assertEquals(2, allRange.summary().totalRuns());
    assertEquals(1, allRange.summary().failedRuns());
  }

  @Test
  void diagnosticsLocatesGatewayDenyAndReportsFiveSteps() {
    String now = Instant.now().toString();
    task("task-b", "教学剧本 3：ToolGateway 限额与安检演示", now);
    run("run-b", "task-b", "BLOCKED", 2000, "PATH_REJECTED", now);
    node("node-b", "run-b", "CODER", "FAILED", "agent-b", now, now);
    model("model-b1", "run-b", "agent-b", "deepseek-flash", "FAILED", 8, 2, 300, "429 Too Many Requests", now);
    tool("tool-b1", "run-b", "agent-b", "WORKSPACE_WRITE", "FAILED", "工具输入不符合约束", 20, now);
    trace(
        "tool-b1",
        "run-b",
        """
        [{"gate":"PATH_WHITELIST","label":"路径白名单与读写隔离","status":"DENY","detail":"目标文件不在任务白名单中"}]
        """,
        now);

    var result = diagnostics.diagnose("run-b");

    assertEquals(RunDiagnostics.CRITICAL, result.healthLevel());
    assertTrue(result.primaryIssue().contains("PATH_WHITELIST"));
    assertTrue(result.primaryIssue().contains("未能写入目标文件"));
    assertEquals(5, result.troubleshootingSteps().size());
    var gateway = result.troubleshootingSteps().get(1);
    assertEquals(2, gateway.stepOrder());
    assertEquals(RunDiagnostics.ISSUE_FOUND, gateway.status());
    assertTrue(gateway.finding().contains("路径白名单与读写隔离"));
    assertEquals(
        "/runs/run-b?nodeId=node-b&glassbox=gateway", gateway.targetRoute());
    assertEquals(
        RunDiagnostics.ISSUE_FOUND, result.troubleshootingSteps().get(2).status());
    assertTrue(result.troubleshootingSteps().get(2).finding().contains("429 限流"));
  }

  @Test
  void healthyRunGetsAllLooksOkSteps() {
    String now = Instant.now().toString();
    task("task-a", "实现单文件番茄钟", now);
    run("run-a", "task-a", "COMPLETED", 1000, null, now);
    node("node-a", "run-a", "ANALYST", "COMPLETED", "agent-a", now, now);
    model("model-a1", "run-a", "agent-a", "mock", "COMPLETED", 10, 5, 100, null, now);
    tool("tool-a1", "run-a", "agent-a", "WORKSPACE_READ", "COMPLETED", null, 30, now);

    var result = diagnostics.diagnose("run-a");

    assertEquals(RunDiagnostics.HEALTHY, result.healthLevel());
    assertEquals(5, result.troubleshootingSteps().size());
    assertTrue(
        result.troubleshootingSteps().stream()
            .allMatch(step -> RunDiagnostics.LOOKS_OK.equals(step.status())));
    assertTrue(result.primaryIssue().contains("健康"));
  }

  /**
   * 单遍分桶必须把每个运行精确计入它自己的小时桶，且不重复、不遗漏。
   *
   * <p>回归：旧实现是「桶 × 运行」双重循环，逻辑正确但复杂度浪费；改造后必须逐点等价。
   */
  @Test
  void timeSeriesCountsEachRunExactlyOnceInsideItsOwnBucket() {
    java.time.Instant now = java.time.Instant.now();
    String taskId = java.util.UUID.randomUUID().toString();
    task(taskId, "分桶样本", now.toString());
    // 三个运行分别落在当前小时、1 小时前与 2 小时前；第四个落在 25 小时前，超出 24h 窗口。
    for (int hourOffset = 0; hourOffset < 3; hourOffset++) {
      run(
          java.util.UUID.randomUUID().toString(),
          taskId,
          "COMPLETED",
          1000L + hourOffset,
          null,
          now.minus(hourOffset, java.time.temporal.ChronoUnit.HOURS).toString());
    }
    run(
        java.util.UUID.randomUUID().toString(),
        taskId,
        "FAILED",
        500L,
        "UNCLASSIFIED_FAILURE",
        now.minus(25, java.time.temporal.ChronoUnit.HOURS).toString());

    var result = dashboard.dashboard("24h", null, null);

    assertEquals(3, result.summary().totalRuns(), "24h 窗口只应统计窗口内的三个运行，窗口外的不计入");
    long bucketed = result.timeSeries().stream().mapToLong(point -> point.runCount()).sum();
    assertEquals(3, bucketed, "24h 分桶必须恰好覆盖窗口内的三个运行且不重复");
    long successes = result.timeSeries().stream().mapToLong(point -> point.successCount()).sum();
    assertEquals(3, successes, "三个 COMPLETED 必须落在各自小时桶");
  }

  /**
   * 分片查询：超过单批 500 的运行集合必须全部被统计，绝不因 IN 分片丢失数据。
   */
  @Test
  void shardedInQueriesKeepAllRowsBeyondOneBatch() {
    java.time.Instant now = java.time.Instant.now();
    String taskId = java.util.UUID.randomUUID().toString();
    task(taskId, "分片样本", now.toString());
    int total = 501;
    var batch = new java.util.ArrayList<Object[]>();
    for (int index = 0; index < total; index++) {
      String runId = java.util.UUID.randomUUID().toString();
      batch.add(
          new Object[] {
            runId, taskId, "COMPLETED", "分片原因", 0, 0, 1000L, now.toString(), now.toString(), null
          });
      db.update(
          """
          INSERT INTO plan_nodes(id,run_id,sequence_no,role_code,status,agent_id,started_at,finished_at)
          VALUES(?,?,?,?,?,?,?,?)
          """,
          java.util.UUID.randomUUID().toString(),
          runId,
          1,
          "ANALYST",
          "COMPLETED",
          java.util.UUID.randomUUID().toString(),
          now.toString(),
          now.toString());
    }
    db.batchUpdate(
        """
        INSERT INTO runs(id,task_id,status,reason,rounds,tools,elapsed_ms,created_at,updated_at,stop_cause)
        VALUES(?,?,?,?,?,?,?,?,?,?)
        """,
        batch);

    var result = dashboard.dashboard("24h", null, null);

    assertEquals(total, result.summary().totalRuns(), "分片后必须统计到全部 501 个运行");
    long bucketed = result.timeSeries().stream().mapToLong(point -> point.runCount()).sum();
    assertEquals(total, bucketed, "分桶同样不能因分片丢行");
  }

  private void task(String id, String goal, String now) {    db.update(
        "INSERT INTO tasks(id,goal,scenario,created_at) VALUES(?,?,?,?)",
        id,
        goal,
        "normal",
        now);
  }

  private void run(
      String id, String taskId, String status, long elapsed, String stopCause, String now) {
    db.update(
        """
        INSERT INTO runs(id,task_id,status,reason,rounds,tools,elapsed_ms,created_at,updated_at,stop_cause)
        VALUES(?,?,?,?,?,?,?,?,?,?)
        """,
        id,
        taskId,
        status,
        "测试原因",
        0,
        0,
        elapsed,
        now,
        now,
        stopCause);
  }

  private void node(
      String id,
      String runId,
      String role,
      String status,
      String agentId,
      String startedAt,
      String finishedAt) {
    db.update(
        """
        INSERT INTO plan_nodes(id,run_id,sequence_no,role_code,status,agent_id,started_at,finished_at)
        VALUES(?,?,?,?,?,?,?,?)
        """,
        id,
        runId,
        1,
        role,
        status,
        agentId,
        startedAt,
        finishedAt);
  }

  private void model(
      String id,
      String runId,
      String agentId,
      String modelName,
      String status,
      int input,
      int output,
      long duration,
      String error,
      String now) {
    db.update(
        """
        INSERT INTO model_invocations(id,run_id,agent_id,model_name,status,input_tokens,
          output_tokens,duration_ms,error_message,created_at,updated_at)
        VALUES(?,?,?,?,?,?,?,?,?,?,?)
        """,
        id,
        runId,
        agentId,
        modelName,
        status,
        input,
        output,
        duration,
        error,
        now,
        now);
  }

  private void tool(
      String id,
      String runId,
      String agentId,
      String toolName,
      String status,
      String error,
      long duration,
      String now) {
    db.update(
        """
        INSERT INTO tool_invocations(id,run_id,agent_id,tool_name,status,error_message,
          duration_ms,created_at,updated_at)
        VALUES(?,?,?,?,?,?,?,?,?)
        """,
        id,
        runId,
        agentId,
        toolName,
        status,
        error,
        duration,
        now,
        now);
  }

  private void trace(String toolCallId, String runId, String checks, String now) {
    db.update(
        """
        INSERT INTO tool_call_traces(tool_call_id,run_id,input_args_json,gateway_checks_json,
          full_result,result_truncated,created_at)
        VALUES(?,?,?,?,?,?,?)
        """,
        toolCallId,
        runId,
        "{}",
        checks,
        "{}",
        false,
        now);
  }
}
