package dev.workbench.observability;

import static org.junit.jupiter.api.Assertions.*;

import dev.workbench.app.WorkbenchApplication;
import dev.workbench.audit.AuditService;
import dev.workbench.report.HtmlReportService;
import dev.workbench.task.RunRecordService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** 任务 7：脱敏、审计、告警定位和环境自检。 */
@SpringBootTest(
    classes = WorkbenchApplication.class,
    properties = {
      "spring.datasource.url=jdbc:h2:mem:task7security;DB_CLOSE_DELAY=-1",
      "workbench.workspace-root=${java.io.tmpdir}/agent-workbench-task7-security"
    })
class Task7SecurityIntegrationTest {
  @Autowired AuditService audit;
  @Autowired AlertService alerts;
  @Autowired SelfCheckService selfCheck;
  @Autowired JdbcTemplate db;

  @Test
  void redactsPathsEmailsAndCredentialShapes() {
    String redacted =
        HtmlReportService.redact(
            "<p>C:\\Users\\Qi\\My Folder\\secret file.py\n/home/qi/My Folder/secret\na@b.com sk-abcdefgh12345 Bearer abcdefghijk</p>");
    assertFalse(redacted.contains("C:\\Users"));
    assertFalse(redacted.contains("My Folder"));
    assertFalse(redacted.contains("secret file.py"));
    assertFalse(redacted.contains("/home/qi"));
    assertFalse(redacted.contains("a@b.com"));
    assertFalse(redacted.contains("sk-abcdefgh12345"));
    assertFalse(redacted.contains("Bearer abcdefghijk"));
    assertTrue(redacted.contains("已脱敏"));
  }

  @Test
  void auditAndAlertsAreTraceable() {
    audit.record("TEST_ACTION", "TEST_TARGET", "id-1", "RECORDED", "固定说明");
    assertTrue(
        audit.list(20).stream()
            .anyMatch(row -> "TEST_ACTION".equals(row.get("ACTION"))
                && "id-1".equals(row.get("TARGET_ID"))));

    String runId = RunRecordService.id();
    db.update(
        "INSERT INTO runs(id,task_id,parent_id,status,reason,result,rounds,tools,max_rounds,"
            + "max_tools,max_seconds,elapsed_ms,created_at,updated_at)"
            + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        runId,
        "task-7",
        null,
        "FAILED",
        "测试失败",
        null,
        0,
        0,
        4,
        2,
        30,
        1,
        RunRecordService.now(),
        RunRecordService.now());
    assertTrue(
        alerts.list().stream()
            .anyMatch(alert -> runId.equals(alert.runId()) && "RUN_FAILED".equals(alert.code())));
  }

  @Test
  void selfCheckReportsDatabaseAndWorkspaceWithoutSecrets() {
    var result = selfCheck.check();
    assertNotNull(result.status());
    assertTrue(result.checks().stream().anyMatch(check -> "DATABASE".equals(check.name())));
    assertTrue(result.checks().stream().anyMatch(check -> "WORKSPACE_ROOT".equals(check.name())));
    assertFalse(
        result.checks().stream()
            .anyMatch(
                check ->
                    check.detail() != null
                        && (check.detail().contains("sk-") || check.detail().contains("Bearer "))));
  }
}
