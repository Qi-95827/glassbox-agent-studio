package dev.workbench.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 不依赖本机历史副本的旧库升级验证：先只跑 V1 建旧库并写入历史任务/运行，再完整升级到最新并连续两次迁移。
 *
 * <p>这样任意机器与 CI 每次 {@code mvn test} 都能真跑「旧库 → V18 + 双启动」链路，不再因为本机缺少
 * 2026-11-11 副本而跳过历史升级断言。
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

    var flyway =
        Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration").load();
    flyway.migrate();
    // 第二次启动：迁移必须幂等，历史数据不因再次迁移而丢失。
    var second = flyway.migrate();
    assertEquals(0, second.migrationsExecuted, "第二次启动不应重复执行迁移");

    try (var connection = DriverManager.getConnection(url, "sa", "");
        var statement = connection.createStatement()) {
      try (var rows = statement.executeQuery("select goal from tasks where id = '" + TASK_ID + "'")) {
        assertTrue(rows.next(), "升级后历史任务必须可读");
        assertEquals("历史任务必须可读", rows.getString(1));
      }
      try (var rows =
          statement.executeQuery("select status from runs where id = '" + RUN_ID + "'")) {
        assertTrue(rows.next(), "升级后历史运行必须可读");
        assertEquals("COMPLETED", rows.getString(1));
      }
      try (var rows =
          statement.executeQuery(
              "select count(*) from \"flyway_schema_history\" where \"success\" = true")) {
        assertTrue(rows.next());
        assertTrue(rows.getInt(1) >= 18, "至少应用了 V1~V18 全部迁移");
      }
      try (var rows =
          statement.executeQuery(
              "select count(*) from \"flyway_schema_history\" where \"success\" = false")) {
        assertTrue(rows.next());
        assertEquals(0, rows.getInt(1), "不得留下失败的迁移记录");
      }
      try (var rows = statement.executeQuery("select count(*) from run_iterations")) {
        assertTrue(rows.next());
        assertFalse(rows.getInt(1) < 0);
      }
    }
  }
}
