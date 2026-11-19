package dev.workbench;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.DriverManager;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/** 在隔离的旧版结构上运行当前初始化脚本，不触碰用户实际数据库。 */
class SchemaMigrationTest {
  @Test
  void legacyPlanDependenciesAreBackfilledIdempotentlyWithoutInventingNodes() throws Exception {
    String name = "plan_migration_" + UUID.randomUUID().toString().replace("-", "");
    String run = UUID.randomUUID().toString();
    String missingRun = UUID.randomUUID().toString();
    String analyst = UUID.randomUUID().toString();
    String reviewer = UUID.randomUUID().toString();
    String orphan = UUID.randomUUID().toString();
    try (var connection = DriverManager.getConnection("jdbc:h2:mem:" + name, "sa", "")) {
      try (var sql =
          connection.prepareStatement(
              "CREATE TABLE plan_nodes(id VARCHAR(36) PRIMARY KEY,run_id VARCHAR(36) NOT"
                  + " NULL,sequence_no INT NOT NULL,role_code VARCHAR(30) NOT"
                  + " NULL,prerequisite_role VARCHAR(30),status VARCHAR(30) NOT NULL,agent_id"
                  + " VARCHAR(36),started_at VARCHAR(40),finished_at"
                  + " VARCHAR(40),UNIQUE(run_id,role_code),UNIQUE(run_id,sequence_no),UNIQUE(agent_id))")) {
        sql.execute();
      }
      try (var sql =
          connection.prepareStatement(
              "INSERT INTO plan_nodes(id,run_id,sequence_no,role_code,prerequisite_role,status)"
                  + " VALUES(?,?,?,?,?,?)")) {
        sql.setString(1, analyst);
        sql.setString(2, run);
        sql.setInt(3, 1);
        sql.setString(4, "ANALYST");
        sql.setString(5, null);
        sql.setString(6, "COMPLETED");
        sql.executeUpdate();
        sql.setString(1, reviewer);
        sql.setString(2, run);
        sql.setInt(3, 2);
        sql.setString(4, "REVIEWER");
        sql.setString(5, "ANALYST");
        sql.setString(6, "PENDING");
        sql.executeUpdate();
        sql.setString(1, orphan);
        sql.setString(2, missingRun);
        sql.setInt(3, 1);
        sql.setString(4, "REVIEWER");
        sql.setString(5, "ANALYST");
        sql.setString(6, "PENDING");
        sql.executeUpdate();
      }
      ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
      ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
      var db =
          new org.springframework.jdbc.core.JdbcTemplate(
              new org.springframework.jdbc.datasource.SingleConnectionDataSource(connection, true));
      assertEquals(
          1, db.queryForObject("SELECT COUNT(*) FROM plan_node_dependencies", Integer.class));
      assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM workflow_checks", Integer.class));
      assertEquals(
          "AGENT",
          db.queryForObject(
              "SELECT node_kind FROM plan_nodes WHERE run_id=? ORDER BY sequence_no LIMIT 1",
              String.class,
              run));
      assertEquals(
          analyst,
          db.queryForObject(
              "SELECT prerequisite_id FROM plan_node_dependencies WHERE run_id=? AND node_id="
                  + "(SELECT id FROM plan_nodes WHERE run_id=? ORDER BY sequence_no DESC LIMIT 1)",
              String.class,
              run,
              run));
      assertEquals(
          2,
          db.queryForObject(
              "SELECT COUNT(*) FROM plan_nodes WHERE run_id=? AND definition_id IS NULL"
                  + " AND definition_sha256 IS NULL",
              Integer.class,
              run));
      assertEquals(3, db.queryForObject("SELECT COUNT(*) FROM plan_nodes", Integer.class));
      assertEquals(
          1,
          db.queryForObject(
              "SELECT COUNT(*) FROM plan_nodes WHERE run_id=?", Integer.class, missingRun));
    }
  }

  @Test
  void legacyToolBudgetsMigrateOnceWithoutResurrectingDeletedRows() throws Exception {
    String name = "budget_migration_" + UUID.randomUUID().toString().replace("-", "");
    try (var connection = DriverManager.getConnection("jdbc:h2:mem:" + name, "sa", "")) {
      try (var sql = connection.createStatement()) {
        sql.execute(
            "CREATE TABLE task_tool_budgets (task_id VARCHAR(36) NOT NULL, tool_kind VARCHAR(40)"
                + " NOT NULL, policy_version INT NOT NULL, max_calls INT NOT NULL, PRIMARY"
                + " KEY(task_id, tool_kind), CHECK(policy_version=1), CHECK(tool_kind IN"
                + " ('SIMULATED_INSPECTION','WORKSPACE_READ','WORKSPACE_WRITE')), CHECK(max_calls"
                + " BETWEEN 0 AND 20))");
        sql.execute("INSERT INTO task_tool_budgets VALUES('old-task','WORKSPACE_READ',1,3)");
      }
      ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
      ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
      try (var sql = connection.createStatement()) {
        try (var rows =
            sql.executeQuery(
                "SELECT max_calls FROM task_tool_budgets_v2 WHERE task_id='old-task' AND"
                    + " tool_kind='WORKSPACE_READ'")) {
          assertTrue(rows.next());
          assertEquals(3, rows.getInt(1));
          assertFalse(rows.next());
        }
        sql.execute("INSERT INTO task_tool_budgets_v2 VALUES('new-task','WORKSPACE_SEARCH',1,0)");
        sql.execute("DELETE FROM task_tool_budgets_v2 WHERE task_id='old-task'");
      }
      ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
      try (var sql = connection.createStatement();
          var rows = sql.executeQuery("SELECT COUNT(*) FROM task_tool_budgets_v2")) {
        assertTrue(rows.next());
        assertEquals(1, rows.getInt(1));
      }
    }
  }

  @Test
  void legacyRowsRemainReadableAfterAdditiveMigration() throws Exception {
    String name = "migration_" + UUID.randomUUID().toString().replace("-", "");
    try (var connection = DriverManager.getConnection("jdbc:h2:mem:" + name, "sa", "")) {
      try (var sql = connection.createStatement()) {
        sql.execute(
            "CREATE TABLE tasks(id VARCHAR(36) PRIMARY KEY,goal VARCHAR(500) NOT NULL,scenario"
                + " VARCHAR(20) NOT NULL,created_at VARCHAR(40) NOT NULL)");
        sql.execute(
            "CREATE TABLE runs(id VARCHAR(36) PRIMARY KEY,task_id VARCHAR(36) NOT NULL,parent_id"
                + " VARCHAR(36),status VARCHAR(30) NOT NULL,reason VARCHAR(1000),result"
                + " VARCHAR(2000),rounds INT NOT NULL,tools INT NOT NULL,max_rounds INT NOT"
                + " NULL,max_tools INT NOT NULL,max_seconds INT NOT NULL,elapsed_ms BIGINT NOT"
                + " NULL,created_at VARCHAR(40) NOT NULL,updated_at VARCHAR(40) NOT NULL)");
        sql.execute("INSERT INTO tasks VALUES('old-task','历史目标','normal','2026-01-01T00:00:00Z')");
        sql.execute(
            "INSERT INTO runs"
                + " VALUES('old-run','old-task',NULL,'COMPLETED',NULL,NULL,1,1,4,2,30,100,'2026-01-01T00:00:00Z','2026-01-01T00:00:00Z')");
        sql.execute(
            "CREATE TABLE agent_invocations(id VARCHAR(36) PRIMARY KEY,run_id VARCHAR(36) NOT"
                + " NULL,role_name VARCHAR(80) NOT NULL,task_goal VARCHAR(500) NOT NULL,status"
                + " VARCHAR(30) NOT NULL,result VARCHAR(2000),created_at VARCHAR(40) NOT"
                + " NULL,updated_at VARCHAR(40) NOT NULL)");
        sql.execute(
            "INSERT INTO agent_invocations VALUES('old-agent','old-run','分析"
                + " Agent','历史目标','COMPLETED','结果','2026-01-01T00:00:00Z','2026-01-01T00:00:00Z')");
        sql.execute(
            "CREATE TABLE bug_checks(id VARCHAR(36) PRIMARY KEY,bug_id VARCHAR(80) NOT"
                + " NULL,workspace_id VARCHAR(36) NOT NULL,kind VARCHAR(30) NOT NULL,status"
                + " VARCHAR(30) NOT NULL,output CLOB,duration_ms BIGINT NOT NULL,created_at"
                + " VARCHAR(40) NOT NULL)");
        sql.execute(
            "INSERT INTO bug_checks"
                + " VALUES('old-check','MOCK-PY-001','old-workspace','REPRODUCE','FAILURES_OBSERVED',NULL,10,'2026-01-01T00:00:00Z')");
        sql.execute(
            "CREATE TABLE evaluation_batches(id VARCHAR(36) PRIMARY KEY,suite VARCHAR(40) NOT"
                + " NULL,status VARCHAR(30) NOT NULL,created_at VARCHAR(40) NOT NULL,finished_at"
                + " VARCHAR(40))");
        sql.execute(
            "INSERT INTO evaluation_batches"
                + " VALUES('old-batch','LOCAL_AGENT_V1','COMPLETED','2026-01-01T00:00:00Z',NULL)");
        sql.execute(
            "CREATE TABLE agent_tool_grants(agent_id VARCHAR(36) NOT NULL,tool_kind VARCHAR(40) NOT"
                + " NULL,PRIMARY KEY(agent_id,tool_kind))");
        sql.execute("INSERT INTO agent_tool_grants VALUES('old-agent','WORKSPACE_READ')");
        sql.execute(
            "CREATE TABLE tool_invocations(id VARCHAR(36) PRIMARY KEY,run_id VARCHAR(36) NOT"
                + " NULL,agent_id VARCHAR(36) NOT NULL,tool_name VARCHAR(80) NOT NULL,status"
                + " VARCHAR(30) NOT NULL,result VARCHAR(2000),error_message"
                + " VARCHAR(1000),duration_ms BIGINT,created_at VARCHAR(40) NOT NULL,updated_at"
                + " VARCHAR(40) NOT NULL)");
        sql.execute(
            "INSERT INTO tool_invocations"
                + " VALUES('old-tool','old-run','old-agent','读取授权文件','COMPLETED',NULL,NULL,10,'2026-01-01T00:00:00Z','2026-01-01T00:00:00Z')");
      }
      ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
      try (var sql = connection.createStatement();
          var rows = sql.executeQuery("SELECT stop_cause FROM runs WHERE id='old-run'")) {
        assertTrue(rows.next());
        assertNull(rows.getObject(1));
      }
      try (var sql = connection.createStatement();
          var rows = sql.executeQuery("SELECT run_id FROM bug_checks WHERE id='old-check'")) {
        assertTrue(rows.next());
        assertNull(rows.getObject(1));
      }
      try (var sql = connection.createStatement();
          var rows =
              sql.executeQuery(
                  "SELECT definition_json,definition_sha256 FROM evaluation_batches WHERE"
                      + " id='old-batch'")) {
        assertTrue(rows.next());
        assertNull(rows.getObject(1));
        assertNull(rows.getObject(2));
      }
      try (var sql = connection.createStatement();
          var rows =
              sql.executeQuery(
                  "SELECT tool_version FROM agent_tool_grants WHERE agent_id='old-agent'")) {
        assertTrue(rows.next());
        assertNull(rows.getObject(1));
      }
      try (var sql = connection.createStatement();
          var rows =
              sql.executeQuery("SELECT tool_version FROM tool_invocations WHERE id='old-tool'")) {
        assertTrue(rows.next());
        assertNull(rows.getObject(1));
      }
      try (var sql = connection.createStatement();
          var rows =
              sql.executeQuery(
                  "SELECT"
                      + " t.memory_enabled,r.max_per_tool,r.status,t.constraints_json,t.acceptance_json,r.max_reported_tokens,r.max_identical_tools,t.entry_kind,r.max_agent_rounds,r.max_agent_tools,r.recovery_dispatch,t.file_scopes_json"
                      + " FROM tasks t JOIN runs r ON r.task_id=t.id")) {
        assertTrue(rows.next());
        assertFalse(rows.getBoolean(1));
        assertEquals(20, rows.getInt(2));
        assertEquals("COMPLETED", rows.getString(3));
        assertEquals("[]", rows.getString(4));
        assertEquals("[]", rows.getString(5));
        assertNull(rows.getObject(6));
        assertNull(rows.getObject(7));
        assertEquals("GENERAL", rows.getString(8));
        assertNull(rows.getObject(9));
        assertNull(rows.getObject(10));
        assertEquals("UNKNOWN", rows.getString(11));
        assertEquals("[]", rows.getString(12));
      }
      try (var sql = connection.createStatement();
          var rows = sql.executeQuery("SELECT COUNT(*) FROM plan_nodes WHERE run_id='old-run'")) {
        assertTrue(rows.next());
        assertEquals(0, rows.getInt(1));
      }
      try (var sql = connection.createStatement();
          var rows =
              sql.executeQuery(
                  "SELECT role_code,role_version,definition_id,definition_version,definition_sha256"
                      + " FROM agent_invocations WHERE id='old-agent'")) {
        assertTrue(rows.next());
        assertNull(rows.getString(1));
        assertNull(rows.getObject(2));
        assertNull(rows.getString(3));
        assertNull(rows.getObject(4));
        assertNull(rows.getString(5));
      }
    }
  }

  @Test
  void schemaReappliesAfterDynamicPlanWithSystemCheckNode() throws Exception {
    String name = "restart_migration_" + UUID.randomUUID().toString().replace("-", "");
    String runId = UUID.randomUUID().toString();
    String planId = UUID.randomUUID().toString();
    try (var connection = DriverManager.getConnection("jdbc:h2:mem:" + name, "sa", "")) {
      ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
      var db =
          new org.springframework.jdbc.core.JdbcTemplate(
              new org.springframework.jdbc.datasource.SingleConnectionDataSource(connection, true));
      db.update(
          "INSERT INTO tasks(id,goal,scenario,created_at) VALUES('restart-task','重启验证','normal',?)",
          "2026-01-01T00:00:00Z");
      db.update(
          "INSERT INTO"
              + " runs(id,task_id,status,rounds,tools,max_rounds,max_tools,max_seconds,elapsed_ms,created_at,updated_at)"
              + " VALUES(?, 'restart-task','COMPLETED',0,0,8,6,90,10,?,?)",
          runId,
          "2026-01-01T00:00:00Z",
          "2026-01-01T00:00:00Z");
      db.update(
          "INSERT INTO"
              + " workflow_plans(id,run_id,plan_version,template_id,template_version,review_required,approved_at,scope_type,authorization_sha256)"
              + " VALUES(?,?,1,'workspace-analysis',2,false,?,'WORKSPACE_READ_ONLY',?)",
          planId,
          runId,
          "2026-01-01T00:00:01Z",
          "a".repeat(64));
      db.update(
          "INSERT INTO plan_nodes(id,run_id,sequence_no,status,plan_version_id,node_key,node_label,"
              + "phase_key,permission_role,definition_id,definition_version,definition_sha256,node_kind,workspace_read)"
              + " VALUES(?,?,1,'PENDING',?,'inspect','分析授权文件','analysis','ANALYST','workspace-analysis',1,?,"
              + " 'AGENT',TRUE)",
          UUID.randomUUID().toString(),
          runId,
          planId,
          "b".repeat(64));
      db.update(
          "INSERT INTO"
              + " plan_nodes(id,run_id,sequence_no,status,plan_version_id,node_key,node_label,phase_key,node_kind,check_id,check_version)"
              + " VALUES(?,?,2,'PENDING',?,'evidence-check',"
              + "'核对授权读取证据','verification','SYSTEM_CHECK','authorized-read-evidence',1)",
          UUID.randomUUID().toString(),
          runId,
          planId);

      // 应用在同一数据库上重启时会再次执行完整脚本；旧的 shape 约束不得因检查节点而失败。
      ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
      ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
      assertEquals(2, db.queryForObject("SELECT COUNT(*) FROM plan_nodes", Integer.class));
      assertEquals(
          "SYSTEM_CHECK",
          db.queryForObject(
              "SELECT node_kind FROM plan_nodes WHERE check_id='authorized-read-evidence'",
              String.class));
      assertEquals(
          1,
          db.queryForObject(
              "SELECT COUNT(*) FROM information_schema.table_constraints WHERE"
                  + " table_name='PLAN_NODES' AND constraint_name='PLAN_NODE_SHAPE_V2'",
              Integer.class));
    }
  }
}
