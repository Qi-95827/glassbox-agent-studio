package dev.workbench.persistence;

import static org.junit.jupiter.api.Assertions.*;

import dev.workbench.app.WorkbenchApplication;
import dev.workbench.task.RunRecordService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;

/** 同一份隔离旧 H2 副本连续启动两次，核对历史、回填标记和 JPA 会话读取。 */
@EnabledIf("legacyCopyAvailable")
class LegacyH2DoubleStartupIntegrationTest {
  private static final Path SOURCE = Path.of("data", "workbench.mv.db");
  private static final String OLD_TASK = "44e0466a-1263-4eca-9a5d-06a382fbbc4d";
  private static final String OLD_RUN = "75869944-3c0a-493a-9f1e-7d87a65df69f";

  static boolean legacyCopyAvailable() {
    return Files.exists(SOURCE);
  }

  @Test
  void startsTwiceOnSameCopyAndReadsHistoryBackfillAndSession() throws Exception {
    Path dir = Files.createTempDirectory("agent-workbench-legacy-double-");
    Files.copy(SOURCE, dir.resolve("workbench.mv.db"), StandardCopyOption.REPLACE_EXISTING);
    String url =
        "jdbc:h2:file:"
            + dir.resolve("workbench").toString().replace('\\', '/')
            + ";DB_CLOSE_ON_EXIT=FALSE";

    try (var first = start(url, dir)) {
      assertHistory(first.getBean(RunRecordService.class), first.getBean(JdbcTemplate.class));
      var sessions = first.getBean(AgentStateStore.class);
      sessions.save(
          "legacy-user",
          "restart-session",
          "agent_state",
          AgentState.builder().sessionId("restart-session").summary("重启后仍可读取").build());
    }

    try (var second = start(url, dir)) {
      assertHistory(second.getBean(RunRecordService.class), second.getBean(JdbcTemplate.class));
      var sessions = second.getBean(AgentStateStore.class);
      var loaded =
          sessions.get("legacy-user", "restart-session", "agent_state", AgentState.class);
      assertTrue(loaded.isPresent());
      assertEquals("重启后仍可读取", loaded.orElseThrow().getSummary());
      assertTrue(
          sessions.get("other-user", "restart-session", "agent_state", AgentState.class).isEmpty());
    }
  }

  private static void assertHistory(RunRecordService runs, JdbcTemplate db) {
    Map<String, Object> task = runs.task(OLD_TASK);
    assertNotNull(task.get("GOAL"));
    assertFalse(task.get("GOAL").toString().isBlank());
    assertEquals("COMPLETED", runs.run(OLD_RUN).get("STATUS"));
    assertFalse(runs.events(OLD_RUN).isEmpty());
    assertFalse(runs.checkpoints(OLD_RUN).isEmpty());
    assertEquals(
        0,
        db.queryForObject(
            "SELECT COUNT(*) FROM events WHERE run_id=? AND run_seq IS NULL",
            Integer.class,
            OLD_RUN));
    assertEquals(
        1,
        db.queryForObject(
            "SELECT COUNT(*) FROM workbench_schema_migrations WHERE migration_id='event_run_seq_v1'",
            Integer.class));
  }

  private static ConfigurableApplicationContext start(String url, Path dir) {
    String databaseFile = dir.resolve("workbench").toString().replace('\\', '/');
    ConfigurableApplicationContext context =
        new SpringApplicationBuilder(WorkbenchApplication.class)
            .web(WebApplicationType.NONE)
            // 初始化器在上下文刷新前执行，地址不对时先失败，不会先打开任何数据库连接。
            .initializers(new IsolatedDatabaseAddressCheck(url))
            // 命令行参数优先级高于 application.properties，确保不打开仓库里的原库。
            .run(
                "--spring.datasource.url=" + url,
                "--spring.datasource.username=sa",
                "--spring.datasource.password=",
                "--spring.sql.init.mode=always",
                "--spring.jpa.hibernate.ddl-auto=none",
                "--workbench.workspace-root=" + dir.resolve("workspaces"));
    assertUsesIsolatedCopy(context, databaseFile);
    return context;
  }

  /** 在 Spring 初始化数据源之前核对生效地址。 */
  private static final class IsolatedDatabaseAddressCheck
      implements ApplicationContextInitializer<ConfigurableApplicationContext> {
    private final String expectedUrl;

    private IsolatedDatabaseAddressCheck(String expectedUrl) {
      this.expectedUrl = expectedUrl;
    }

    @Override
    public void initialize(ConfigurableApplicationContext context) {
      String configured = context.getEnvironment().getProperty("spring.datasource.url");
      if (!expectedUrl.equals(configured)) {
        throw new IllegalStateException(
            "数据源地址未指向隔离副本，拒绝启动。期望 "
                + expectedUrl
                + "，实际 "
                + configured);
      }
    }
  }

  /** 启动后确认真正建立的连接也指向隔离副本。 */
  private static void assertUsesIsolatedCopy(
      ConfigurableApplicationContext context, String databaseFile) {
    String effective =
        context
            .getBean(JdbcTemplate.class)
            .execute(
                (org.springframework.jdbc.core.ConnectionCallback<String>)
                    connection -> connection.getMetaData().getURL());
    assertNotNull(effective);
    assertTrue(
        effective.startsWith("jdbc:h2:file:" + databaseFile),
        "实际连接地址必须是隔离副本，实际为 " + effective);
    assertFalse(
        effective.contains("data/workbench.mv.db"), "实际连接地址不能是仓库原库：" + effective);
  }
}
