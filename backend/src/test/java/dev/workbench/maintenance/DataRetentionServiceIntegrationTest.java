package dev.workbench.maintenance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.workbench.app.WorkbenchApplication;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 冷数据 TTL 归档：只清理「过期且所属运行已终态」的大对象。
 *
 * <p>硬红线：活跃运行（{@code PENDING}/{@code RUNNING}/{@code PAUSED}）的载荷、产物与会话无条件保留；
 * 近期终态运行同样保留；{@code tasks}/{@code runs}/{@code events} 审计主干一行都不能少。
 */
@SpringBootTest(
    classes = WorkbenchApplication.class,
    properties = {
      "spring.datasource.url=jdbc:h2:mem:data_retention;DB_CLOSE_DELAY=-1",
      "workbench.workspace-root=${java.io.tmpdir}/agent-workbench-retention",
      "workbench.retention.enabled=true",
      "workbench.retention.payload-days=30",
      "workbench.retention.artifact-days=30",
      "workbench.retention.session-days=14"
    })
class DataRetentionServiceIntegrationTest {
  @Autowired DataRetentionService retention;
  @Autowired JdbcTemplate db;

  /** 每个用例从干净证据开始：避免同一 H2 库内前一个用例的过期数据影响本用例的删除计数。 */
  @org.junit.jupiter.api.BeforeEach
  void cleanEvidence() {
    for (String table :
        new String[] {
          "model_call_payloads",
          "tool_call_traces",
          "context_artifacts",
          "agentscope_sessions",
          "events",
          "runs",
          "tasks"
        }) {
      db.execute("DELETE FROM " + table);
    }
  }

  @Test
  void purgesOnlyExpiredTerminalColdDataAndKeepsEverythingElse() {
    Instant now = Instant.now();
    String old = now.minus(60, ChronoUnit.DAYS).toString();
    String recent = now.minus(1, ChronoUnit.DAYS).toString();

    String expiredTerminal = terminalRun("COMPLETED", old);
    String recentTerminal = terminalRun("COMPLETED", recent);
    String expiredRunning = terminalRun("RUNNING", old);
    String expiredPaused = terminalRun("PAUSED", old);

    // 过期终态运行的冷数据：必须被清理。
    payload("payload-old", expiredTerminal, old);
    trace("trace-old", expiredTerminal, old);
    artifact("artifact-old", expiredTerminal, old);
    // 近期终态运行的冷数据：保留。
    payload("payload-recent", recentTerminal, recent);
    trace("trace-recent", recentTerminal, recent);
    artifact("artifact-recent", recentTerminal, recent);
    // 活跃运行（即便创建时间很旧）：全部保留。
    payload("payload-running", expiredRunning, old);
    trace("trace-running", expiredRunning, old);
    artifact("artifact-running", expiredRunning, old);
    payload("payload-paused", expiredPaused, old);
    // 无运行归属的过期产物：按过期清理。
    artifact("artifact-orphan", null, old);

    session("session-old", now.minus(30, ChronoUnit.DAYS));
    session("session-recent", now.minus(1, ChronoUnit.DAYS));

    long tasksBefore = count("tasks");
    long runsBefore = count("runs");
    long eventsBefore = count("events");

    var report = retention.purgeExpired();

    assertEquals(1, report.deletedPayloads(), "只应删除过期终态运行的模型载荷");
    assertEquals(1, report.deletedToolTraces(), "只应删除过期终态运行的工具轨迹");
    assertEquals(2, report.deletedArtifacts(), "过期终态 + 无归属产物应被清理");
    assertEquals(1, report.deletedSessions(), "只应删除超过会话保留期的 Agent 会话");

    assertTrue(!exists("model_call_payloads", "model_call_id", "payload-old"));
    assertTrue(!exists("tool_call_traces", "tool_call_id", "trace-old"));
    assertTrue(!exists("context_artifacts", "id", "artifact-old"));
    assertTrue(!exists("context_artifacts", "id", "artifact-orphan"));

    assertTrue(exists("model_call_payloads", "model_call_id", "payload-recent"), "近期载荷必须保留");
    assertTrue(exists("context_artifacts", "id", "artifact-recent"), "近期产物必须保留");
    assertTrue(exists("model_call_payloads", "model_call_id", "payload-running"), "活跃运行载荷必须保留");
    assertTrue(exists("tool_call_traces", "tool_call_id", "trace-running"), "活跃运行轨迹必须保留");
    assertTrue(exists("context_artifacts", "id", "artifact-running"), "活跃运行产物必须保留");
    assertTrue(exists("model_call_payloads", "model_call_id", "payload-paused"), "暂停运行载荷必须保留");

    assertTrue(!exists("agentscope_sessions", "session_id", "session-old"), "过期会话应被清理");
    assertTrue(exists("agentscope_sessions", "session_id", "session-recent"), "近期会话必须保留");

    // 审计主干零损耗。
    assertEquals(tasksBefore, count("tasks"), "tasks 绝不能被清理");
    assertEquals(runsBefore, count("runs"), "runs 绝不能被清理");
    assertEquals(eventsBefore, count("events"), "events 绝不能被清理");
    assertTrue(exists("runs", "id", expiredTerminal));
    assertTrue(exists("runs", "id", expiredRunning));
    assertTrue(exists("runs", "id", expiredPaused));
  }

  @Test
  void disabledRetentionDeletesNothing() {
    var service =
        new DataRetentionService(db, false, 30, 30, 14);
    Instant old = Instant.now().minus(90, ChronoUnit.DAYS);
    String run = terminalRun("COMPLETED", old.toString());
    payload("payload-disabled", run, old.toString());
    var report = service.purgeExpired();
    assertEquals(new DataRetentionService.RetentionReport(0, 0, 0, 0), report);
    assertTrue(exists("model_call_payloads", "model_call_id", "payload-disabled"));
  }

  private String terminalRun(String status, String createdAt) {
    String taskId = UUID.randomUUID().toString();
    db.update(
        "INSERT INTO tasks(id,goal,scenario,created_at) VALUES(?,?,?,?)",
        taskId,
        "归档样本任务",
        "normal",
        createdAt);
    String runId = UUID.randomUUID().toString();
    db.update(
        """
        INSERT INTO runs(id,task_id,status,reason,rounds,tools,elapsed_ms,created_at,updated_at,stop_cause)
        VALUES(?,?,?,?,?,?,?,?,?,?)
        """,
        runId,
        taskId,
        status,
        "归档样本",
        0,
        0,
        1000L,
        createdAt,
        createdAt,
        null);
    return runId;
  }

  private void payload(String id, String runId, String createdAt) {
    db.update(
        "INSERT INTO model_call_payloads(model_call_id,run_id,truncated,created_at) VALUES(?,?,?,?)",
        id,
        runId,
        false,
        createdAt);
  }

  private void trace(String id, String runId, String createdAt) {
    db.update(
        "INSERT INTO tool_call_traces(tool_call_id,run_id,result_truncated,created_at) VALUES(?,?,?,?)",
        id,
        runId,
        false,
        createdAt);
  }

  private void artifact(String id, String runId, String createdAt) {
    db.update(
        "INSERT INTO context_artifacts(id,run_id,title,content,char_count,created_at) VALUES(?,?,?,?,?,?)",
        id,
        runId,
        "样本产物",
        "样本正文",
        4,
        createdAt);
  }

  private void session(String id, Instant updatedAt) {
    db.update(
        """
        INSERT INTO agentscope_sessions(session_id,state_key,item_index,state_data,version,created_at,updated_at)
        VALUES(?,?,?,?,?,?,?)
        """,
        id,
        "state",
        0,
        "{}",
        0L,
        java.sql.Timestamp.from(updatedAt),
        java.sql.Timestamp.from(updatedAt));
  }

  private long count(String table) {
    return db.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
  }

  private boolean exists(String table, String keyColumn, String key) {
    Integer matched =
        db.queryForObject(
            "SELECT COUNT(*) FROM " + table + " WHERE " + keyColumn + " = ?", Integer.class, key);
    return matched != null && matched > 0;
  }
}
