package dev.workbench.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.workbench.app.WorkbenchApplication;
import dev.workbench.store.RunRecordService;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import java.nio.file.Path;
import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 不依赖本机历史副本的旧库升级与真实双启动验证：先只跑 V1 建旧库并写入历史任务/运行，
 * 再连续两次完整启动 Spring Boot 上下文（Flyway V1→V18 迁移 + JPA/Store 读取 + 会话持久化重启验证）。
 *
 * <p>这样任意机器与 CI 每次 {@code mvn test} 都能真跑「旧库 → V18 + 两次真实应用重启」链路，不再因为本机缺少
 * 2026-11-11 副本而跳过历史升级与 JPA 回读断言。
 */
class LegacySchemaUpgradeIntegrationTest {
  private static final String TASK_ID = "11111111-1111-4111-8111-111111111111";
  private static final String RUN_ID = "22222222-2222-4222-8222-222222222222";

  @TempDir Path temp;

  @Test
  void v1DatabaseUpgradesToLatestAndHistoryStaysReadableAcrossTwoStartups() throws Exception {
    String url =
        "jdbc:h2:file:"
            + temp.resolve("legacy").toAbsolutePath().toString().replace('\\', '/')
            + ";DB_CLOSE_ON_EXIT=FALSE";
    Flyway.configure()
        .dataSource(url, "sa", "")
        .locations("classpath:db/migration")
        .target("1")
        .load()
        .migrate();
    try (var connection = DriverManager.getConnection(url, "sa", "");
        var statement = connection.createStatement()) {
      statement.executeUpdate(
          "insert into tasks(id, goal, scenario, created_at) values ('"
              + TASK_ID
              + "', '历史任务必须可读', 'NORMAL', '2026-01-01T00:00:00Z')");
      statement.executeUpdate(
          "insert into runs(id, task_id, status, rounds, tools, elapsed_ms, created_at, updated_at)"
              + " values ('"
              + RUN_ID
              + "', '"
              + TASK_ID
              + "', 'COMPLETED', 1, 2, 1000, '2026-01-01T00:00:00Z', '2026-01-01T00:00:01Z')");
    }

    int appliedAfterFirst;
    try (var first = start(url, temp)) {
      assertHistoryReadable(first);
      var db = first.getBean(JdbcTemplate.class);
      Integer count =
          db.queryForObject(
              "select count(*) from \"flyway_schema_history\" where \"success\" = true",
              Integer.class);
      assertNotNull(count);
      assertTrue(count >= 18, "首次启动必须应用 V1~V18 全部迁移");
      appliedAfterFirst = count;
      assertEquals(
          0,
          db.queryForObject(
              "select count(*) from \"flyway_schema_history\" where \"success\" = false",
              Integer.class),
          "不得留下失败的迁移记录");
      first
          .getBean(AgentStateStore.class)
          .save(
              "legacy-synth-user",
              "restart-session",
              "agent_state",
              AgentState.builder().sessionId("restart-session").summary("双启动会话可读").build());
    }

    // 第二次完整启动 Spring Boot：迁移必须幂等，历史任务/运行与 JPA 会话在重启后均完整可读。
    try (var second = start(url, temp)) {
      assertHistoryReadable(second);
      var db = second.getBean(JdbcTemplate.class);
      assertEquals(
          appliedAfterFirst,
          db.queryForObject(
              "select count(*) from \"flyway_schema_history\" where \"success\" = true",
              Integer.class),
          "第二次启动不应重复执行迁移");
      var loaded =
          second
              .getBean(AgentStateStore.class)
              .get("legacy-synth-user", "restart-session", "agent_state", AgentState.class);
      assertTrue(loaded.isPresent(), "JPA 会话在二次启动后必须可读");
      assertEquals("双启动会话可读", loaded.orElseThrow().getSummary());
    }
  }

  private static void assertHistoryReadable(ConfigurableApplicationContext context) {
    var runs = context.getBean(RunRecordService.class);
    assertEquals("历史任务必须可读", runs.task(TASK_ID).get("GOAL"));
    assertEquals("COMPLETED", runs.run(RUN_ID).get("STATUS"));
    var db = context.getBean(JdbcTemplate.class);
    Integer iterations = db.queryForObject("select count(*) from run_iterations", Integer.class);
    assertNotNull(iterations);
    assertFalse(iterations < 0);
  }

  private static ConfigurableApplicationContext start(String url, Path dir) {
    return new SpringApplicationBuilder(WorkbenchApplication.class)
        .web(WebApplicationType.NONE)
        .run(
            "--spring.datasource.url=" + url,
            "--spring.datasource.username=sa",
            "--spring.datasource.password=",
            "--spring.sql.init.mode=never",
            "--spring.jpa.hibernate.ddl-auto=none",
            "--workbench.workspace-root=" + dir.resolve("workspaces"));
  }
}
