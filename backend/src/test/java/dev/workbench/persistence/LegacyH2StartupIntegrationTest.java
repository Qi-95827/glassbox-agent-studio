package dev.workbench.persistence;

import static org.junit.jupiter.api.Assertions.*;

import dev.workbench.app.WorkbenchApplication;
import dev.workbench.store.RunRecordService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import dev.workbench.store.RunRecordService;

/**
 * 隔离旧 H2 副本启动与历史读取。
 *
 * <p>使用工作区 data/workbench.mv.db（2026-11-11 的真实历史副本）复制到临时目录；不修改原库。 若本机没有该副本则跳过，避免把机器路径写死成必跑用例。
 */
@EnabledIf("legacyCopyAvailable")
@SpringBootTest(classes = WorkbenchApplication.class)
class LegacyH2StartupIntegrationTest {
  private static final Path SOURCE = Path.of("data", "workbench.mv.db");
  private static final String OLD_TASK = "44e0466a-1263-4eca-9a5d-06a382fbbc4d";
  private static final String OLD_RUN = "75869944-3c0a-493a-9f1e-7d87a65df69f";
  private static final String OLD_CHECKPOINT = "d07b3010-8f5d-4a9c-b2b3-1a8c1dfeccd2";
  private static String isolatedUrl;

  @Autowired RunRecordService repository;
  @Autowired org.springframework.jdbc.core.JdbcTemplate db;

  @Value("${spring.jpa.hibernate.ddl-auto}")
  String ddlAuto;

  @Value("${spring.datasource.url}")
  String datasourceUrl;

  static boolean legacyCopyAvailable() {
    return Files.exists(SOURCE);
  }

  @DynamicPropertySource
  static void isolatedCopy(DynamicPropertyRegistry registry) throws Exception {
    Path dir = Files.createTempDirectory("agent-workbench-legacy-h2-");
    Files.copy(SOURCE, dir.resolve("workbench.mv.db"), StandardCopyOption.REPLACE_EXISTING);
    String url =
        "jdbc:h2:file:"
            + dir.resolve("workbench").toString().replace('\\', '/')
            + ";DB_CLOSE_ON_EXIT=FALSE";
    registry.add("spring.datasource.url", () -> url);
    registry.add("spring.datasource.username", () -> "sa");
    registry.add("spring.datasource.password", () -> "");
    registry.add("workbench.workspace-root", () -> dir.resolve("workspaces").toString());
    isolatedUrl = url;
  }

  @Test
  void migratedStartupKeepsHistoricalTasksRunsAndEventsReadable() {
    assertEquals("none", ddlAuto, "Hibernate 不应自动建表或改表。");
    assertEquals(isolatedUrl, datasourceUrl, "实际连接地址必须是隔离副本。");
    assertTrue(repository.tasks().size() >= 13, "历史任务应全部可读。");
    Map<String, Object> task = repository.task(OLD_TASK);
    assertNotNull(task.get("GOAL"));
    assertFalse(task.get("GOAL").toString().isBlank());
    assertEquals("COMPLETED", repository.run(OLD_RUN).get("STATUS"));
    assertFalse(repository.events(OLD_RUN).isEmpty(), "历史运行的事件应可读。");
    assertFalse(repository.checkpoints(OLD_RUN).isEmpty(), "历史检查点应可读。");
    assertNotNull(repository.checkpoint(OLD_CHECKPOINT));
    assertEquals(
        1,
        db.queryForObject(
            "SELECT COUNT(*) FROM workbench_schema_migrations WHERE migration_id='tool_budgets_v2'",
            Integer.class));
  }
}
