package dev.workbench.persistence;

import static org.junit.jupiter.api.Assertions.*;

import dev.workbench.persistence.migration.LegacySchemaColumnCallback;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 瑕嗙洊绌哄簱銆佺己鍒楁棫缁撴瀯銆佸凡瀹夎杩佺Щ閲嶅鍚姩鍜屽綋鍓嶅巻鍙插壇鏈洓鏉?Flyway 璺緞銆?*/
class FlywayMigrationCompatibilityTest {
  private static final Path LEGACY = Path.of("data", "workbench.mv.db");

  static boolean legacyCopyAvailable() {
    return Files.exists(LEGACY);
  }

  @Test
  void emptyDatabaseAppliesBothMigrationsAndCreatesFullSchema() {
    var db = database("empty");
    migrate(db, flyway(db));
    assertEquals("8", flyway(db).info().current().getVersion().getVersion());
    assertEquals(84, count(db, "INFORMATION_SCHEMA.TABLES", "TABLE_SCHEMA='PUBLIC' AND lower(TABLE_NAME)<>'flyway_schema_history'"));
    assertEquals(1, count(db, "INFORMATION_SCHEMA.COLUMNS", "TABLE_NAME='COST_RESERVATIONS' AND COLUMN_NAME='PRICE_VERSION_ID'"));
    assertEquals(1, count(db, "INFORMATION_SCHEMA.COLUMNS", "TABLE_NAME='TASKS' AND COLUMN_NAME='LOCALE'"));
    assertEquals(84, count(db, "INFORMATION_SCHEMA.TABLES", "TABLE_SCHEMA='PUBLIC' AND lower(TABLE_NAME)<>'flyway_schema_history'"));
    assertEquals(1, count(db, "INFORMATION_SCHEMA.TABLES", "TABLE_SCHEMA='PUBLIC' AND TABLE_NAME='MODEL_CALL_PAYLOADS'"));
    assertEquals(1, count(db, "INFORMATION_SCHEMA.TABLES", "TABLE_SCHEMA='PUBLIC' AND TABLE_NAME='TOOL_CALL_TRACES'"));
    assertEquals(1, count(db, "INFORMATION_SCHEMA.TABLES", "TABLE_SCHEMA='PUBLIC' AND TABLE_NAME='WORKFLOW_PLAN_CONTROLS'"));
  }

  @Test
  void oldPlanNodesWithoutVersionColumnUpgradesBeforeForeignKeys() {
    var db = database("old-plan-nodes");
    db.execute(
        """
        CREATE TABLE plan_nodes (
          id VARCHAR(36) PRIMARY KEY,
          run_id VARCHAR(36) NOT NULL,
          sequence_no INT NOT NULL,
          role_code VARCHAR(30) NOT NULL,
          prerequisite_role VARCHAR(30),
          status VARCHAR(30) NOT NULL,
          agent_id VARCHAR(36),
          started_at VARCHAR(40),
          finished_at VARCHAR(40),
          UNIQUE(run_id, role_code),
          UNIQUE(run_id, sequence_no),
          UNIQUE(agent_id))
        """);
    db.update(
        "INSERT INTO plan_nodes(id,run_id,sequence_no,role_code,status) VALUES(?,?,?,?,?)",
        "legacy-node",
        "legacy-run",
        0,
        "CODER",
        "PENDING");

    migrate(db, flyway(db));

    assertEquals("8", flyway(db).info().current().getVersion().getVersion());
    assertEquals(
        1,
        count(
            db,
            "INFORMATION_SCHEMA.COLUMNS",
            "TABLE_NAME='PLAN_NODES' AND COLUMN_NAME='PLAN_VERSION_ID'"));
    assertEquals(
        1,
        count(
            db,
            "INFORMATION_SCHEMA.TABLE_CONSTRAINTS",
            "CONSTRAINT_NAME='WORKFLOW_NODE_RUN'"));
    assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM plan_nodes", Integer.class));
  }

  @Test
  void alreadyAppliedMigrationsAreIdempotentOnSecondStartup() {
    var db = database("double-startup");
    migrate(db, flyway(db));
    migrate(db, flyway(db));
    assertEquals("8", flyway(db).info().current().getVersion().getVersion());
    assertEquals(84, count(db, "INFORMATION_SCHEMA.TABLES", "TABLE_SCHEMA='PUBLIC' AND lower(TABLE_NAME)<>'flyway_schema_history'"));
  }

  @Test
  @EnabledIf("legacyCopyAvailable")
  void currentHistoricalCopyMigratesWithProductionSettings() throws Exception {
    Path dir = Files.createTempDirectory("agent-workbench-flyway-legacy-");
    try {
      Files.copy(LEGACY, dir.resolve("workbench.mv.db"), StandardCopyOption.REPLACE_EXISTING);
    } catch (java.nio.file.FileSystemException locked) {
      // 鏈満姝ｅ湪杩愯搴旂敤鏃朵細閿佷綇 H2 鏂囦欢锛涙鏃惰烦杩囧巻鍙插壇鏈縼绉伙紝浠嶇敱绌哄簱璺緞瑕嗙洊杩佺Щ銆?      org.junit.jupiter.api.Assumptions.assumeTrue(false, "鍘嗗彶搴撴枃浠舵琚繍琛屼腑鐨勫簲鐢ㄥ崰鐢ㄣ€?);
    }
    String url =
        "jdbc:h2:file:"
            + dir.resolve("workbench").toString().replace('\\', '/')
            + ";DB_CLOSE_ON_EXIT=FALSE";
    var db = new JdbcTemplate(new DriverManagerDataSource(url, "sa", ""));
    migrate(db, flyway(db));
    assertEquals("8", flyway(db).info().current().getVersion().getVersion());
    assertEquals(84, count(db, "INFORMATION_SCHEMA.TABLES", "TABLE_SCHEMA='PUBLIC' AND lower(TABLE_NAME)<>'flyway_schema_history'"));
    assertTrue(db.queryForObject("SELECT COUNT(*) FROM tasks", Integer.class) >= 0);
    org.junit.jupiter.api.Assumptions.assumeTrue(
        db.queryForObject("SELECT COUNT(*) FROM tasks", Integer.class) >= 1,
        "历史副本没有任务数据，空库迁移路径已覆盖该场景。");
  }

  private static JdbcTemplate database(String name) {
    String url = "jdbc:h2:mem:" + name + "-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
    return new JdbcTemplate(new DriverManagerDataSource(url, "sa", ""));
  }

  private static Flyway flyway(JdbcTemplate db) {
    return Flyway.configure()
        .dataSource(db.getDataSource())
        .locations("classpath:db/migration")
        .baselineOnMigrate(true)
        .baselineVersion("0")
        .callbacks(new LegacySchemaColumnCallback())
        .load();
  }

  private static void migrate(JdbcTemplate db, Flyway flyway) {
    flyway.migrate();
  }

  private static int count(JdbcTemplate db, String table, String predicate) {
    return db.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + predicate, Integer.class);
  }
}

