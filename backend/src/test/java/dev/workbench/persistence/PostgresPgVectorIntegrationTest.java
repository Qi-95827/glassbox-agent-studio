package dev.workbench.persistence;

import static org.junit.jupiter.api.Assertions.*;

import dev.workbench.app.WorkbenchApplication;
import dev.workbench.persistence.entity.AgentScopeSessionEntity;
import dev.workbench.persistence.entity.EvalLabCaseResultEntity;
import dev.workbench.persistence.entity.KnowledgeChunkEntity;
import dev.workbench.persistence.entity.MemoryRecordEntity;
import dev.workbench.persistence.entity.ModelCallPayloadEntity;
import dev.workbench.persistence.repository.SystemCatalogRepository;
import jakarta.persistence.EntityManager;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * PostgreSQL + pgvector 集成验证（仅设置 {@code WORKBENCH_PG_TEST=true} 时执行）。
 *
 * <p>前置：{@code docker compose up -d}。本类同时验证三层事实：
 *
 * <ol>
 *   <li>迁移：pgvector 扩展、HNSW 索引、V13 通用表结构与本轮补齐的历史迁移标记；
 *   <li>运行时 SQL：工作区级向量召回隔离；
 *   <li>JPA/Hibernate 真实读写：{@code @JdbcTypeCode(SqlTypes.LONGVARCHAR)} 映射在 PostgreSQL 的 {@code
 *       TEXT} 列上能写入并回读超过 10KB 的中文长文本（这正是 Hibernate 6 {@code @Lob} 在 pgjdbc 下被当成 OID 大对象而崩溃的场景）。
 * </ol>
 */
@EnabledIfEnvironmentVariable(named = "WORKBENCH_PG_TEST", matches = "true")
@ActiveProfiles("postgres")
@SpringBootTest(classes = WorkbenchApplication.class)
class PostgresPgVectorIntegrationTest {
  private static final String URL =
      System.getenv()
          .getOrDefault("WORKBENCH_PG_URL", "jdbc:postgresql://127.0.0.1:5432/workbench");
  private static final String USER = System.getenv().getOrDefault("WORKBENCH_PG_USER", "workbench");
  private static final String PASSWORD =
      System.getenv().getOrDefault("WORKBENCH_PG_PASSWORD", "workbench");

  /** 超过 10KB 的中文长文本：包含换行、引号、反斜杠与 CLOB/TEXT 边界字符。 */
  private static final String LONG_TEXT = buildLongText();

  @Autowired EntityManager em;
  @Autowired SystemCatalogRepository catalog;
  @Autowired PlatformTransactionManager transactionManager;

  @Test
  void migrationsCreateVectorExtensionHnswAndWorkspaceIsolation() throws Exception {
    try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD)) {
      assertEquals(
          1,
          scalar(connection, "SELECT count(*) FROM pg_extension WHERE extname = 'vector'"),
          "pgvector 扩展必须已启用");
      assertTrue(
          text(
                  connection,
                  "SELECT indexdef FROM pg_indexes WHERE indexname ="
                      + " 'idx_memory_records_embedding_vec'")
              .toLowerCase(java.util.Locale.ROOT)
              .contains("hnsw"),
          "记忆向量必须使用 HNSW 索引");
      assertTrue(
          text(
                  connection,
                  "SELECT indexdef FROM pg_indexes WHERE indexname ="
                      + " 'idx_knowledge_chunks_embedding_vec'")
              .toLowerCase(java.util.Locale.ROOT)
              .contains("hnsw"),
          "知识库分块必须使用 HNSW 索引");
      assertEquals(
          3,
          scalar(
              connection,
              "SELECT count(*) FROM information_schema.tables WHERE table_name IN"
                  + " ('knowledge_documents','knowledge_chunks','context_artifacts')"),
          "V13 通用表结构必须已创建");
      assertEquals(
          3,
          scalar(
              connection,
              "SELECT count(*) FROM workbench_schema_migrations WHERE migration_id IN"
                  + " ('tool_budgets_v2','workflow_check_v1','workflow_plan_v1')"),
          "PostgreSQL 必须补齐与 H2 等价的迁移标记");

      String workspaceA = java.util.UUID.randomUUID().toString();
      String workspaceB = java.util.UUID.randomUUID().toString();
      String idA = java.util.UUID.randomUUID().toString();
      String idB = java.util.UUID.randomUUID().toString();
      insert(connection, idA, workspaceA, vectorLiteral());
      insert(connection, idB, workspaceB, vectorLiteral());
      try {
        var ids = new ArrayList<String>();
        try (var ps =
            connection.prepareStatement(
                "SELECT id FROM memory_records WHERE workspace_id = ? AND status = 'ACTIVE' AND"
                    + " embedding_vec IS NOT NULL ORDER BY embedding_vec <=> ?::vector LIMIT 5")) {
          ps.setString(1, workspaceA);
          ps.setString(2, vectorLiteral());
          try (var rs = ps.executeQuery()) {
            while (rs.next()) ids.add(rs.getString(1));
          }
        }
        assertEquals(List.of(idA), ids, "向量召回必须严格限定在当前工作区");
      } finally {
        try (var ps =
            connection.prepareStatement("DELETE FROM memory_records WHERE id IN (?, ?)")) {
          ps.setString(1, idA);
          ps.setString(2, idB);
          ps.executeUpdate();
        }
      }
    }
  }

  @Test
  void schemaSelfCheckCountsPublicTables() {
    Integer tables = catalog.countPublicTables();

    assertNotNull(tables);
    assertTrue(tables > 10, "系统自检必须能数到 public 下的业务表，实际：" + tables);
  }

  /** Hibernate 6 + pgjdbc：长文本必须写入 TEXT 列并可原样回读。 */
  @Test
  void jpaRoundTripsLongTextOnPostgres() {
    var template = new TransactionTemplate(transactionManager);
    String suffix = java.util.UUID.randomUUID().toString().substring(0, 8);
    String sessionId = "pg-session-" + suffix;
    String chunkId = java.util.UUID.randomUUID().toString();
    String memoryId = java.util.UUID.randomUUID().toString();
    String modelCallId = java.util.UUID.randomUUID().toString();
    String caseId = java.util.UUID.randomUUID().toString();
    String now = java.time.Instant.now().toString();

    template.executeWithoutResult(
        status -> {
          em.persist(new AgentScopeSessionEntity(sessionId, "agent_state", 0, LONG_TEXT, 7L));
          em.persist(
              new KnowledgeChunkEntity(
                  chunkId,
                  java.util.UUID.randomUUID().toString(),
                  java.util.UUID.randomUUID().toString(),
                  0,
                  "长文本章节",
                  LONG_TEXT,
                  LONG_TEXT.length() / 2,
                  "[" + "0.1,".repeat(10) + "0.1]",
                  "pg-it-model",
                  now));
          em.persist(
              new MemoryRecordEntity(
                  memoryId,
                  java.util.UUID.randomUUID().toString(),
                  dev.workbench.domain.MemoryCategory.PROJECT_CONVENTION,
                  "长文本记忆",
                  dev.workbench.domain.MemorySource.USER_CONFIRMED,
                  dev.workbench.domain.MemoryStatus.ACTIVE,
                  1,
                  now,
                  now));
          em.persist(
              new ModelCallPayloadEntity(
                  modelCallId,
                  java.util.UUID.randomUUID().toString(),
                  LONG_TEXT,
                  "[]",
                  LONG_TEXT,
                  "[]",
                  true,
                  now));
          em.persist(
              new EvalLabCaseResultEntity(
                  caseId,
                  java.util.UUID.randomUUID().toString(),
                  "CODING_REFLECTION",
                  "sample-" + suffix,
                  "长文本用例",
                  "long text case",
                  "mini-coding-reflection",
                  1,
                  null,
                  true,
                  1.0,
                  4.5,
                  "{}",
                  "[]",
                  "{}",
                  LONG_TEXT,
                  LONG_TEXT,
                  1_000L,
                  10,
                  20,
                  0.01,
                  now));
          em.flush();
          em.clear();
        });

    template.executeWithoutResult(
        status -> {
          var session =
              em.find(
                  AgentScopeSessionEntity.class,
                  new AgentScopeSessionEntity.Id(sessionId, "agent_state", 0));
          assertNotNull(session, "AgentScope 会话必须可回读");
          assertEquals(LONG_TEXT, session.getStateData());

          var chunk = em.find(KnowledgeChunkEntity.class, chunkId);
          assertNotNull(chunk, "知识库分块必须可回读");
          assertEquals(LONG_TEXT, chunk.getContent());

          var memory = em.find(MemoryRecordEntity.class, memoryId);
          assertNotNull(memory, "记忆正文必须可回读");

          var payload = em.find(ModelCallPayloadEntity.class, modelCallId);
          assertNotNull(payload, "模型调用透视必须可回读");
          assertEquals(LONG_TEXT, payload.getRequestMessagesJson());

          var caseResult = em.find(EvalLabCaseResultEntity.class, caseId);
          assertNotNull(caseResult, "评测用例结果必须可回读");
          assertEquals(LONG_TEXT, caseResult.getRawOutput());

          em.remove(session);
          em.remove(chunk);
          em.remove(memory);
          em.remove(payload);
          em.remove(caseResult);
          em.flush();
        });
  }

  private static String buildLongText() {
    var builder = new StringBuilder("PostgreSQL TEXT 长文本边界验证：中文、引号\"'、反斜杠\\、换行\n");
    while (builder.length() < 12_000) {
      builder.append("第").append(builder.length()).append("段：向量化、上下文压缩与审计证据不得丢失。\n");
    }
    return builder.toString();
  }

  private static String vectorLiteral() {
    var text = new StringBuilder("[1");
    for (int index = 1; index < 1536; index++) text.append(",0");
    return text.append(']').toString();
  }

  private static void insert(Connection connection, String id, String workspace, String vector)
      throws Exception {
    try (var ps =
        connection.prepareStatement(
            "INSERT INTO memory_records (id, workspace_id, category, content, source, status,"
                + " version, memory_type, source_run_id, path_patterns_json, confidence, hit_count,"
                + " last_hit_at, created_at, updated_at, embedding_json, embedding_model,"
                + " embedding_vec) VALUES (?, ?, 'PROJECT_CONVENTION', ?, 'USER_CONFIRMED',"
                + " 'ACTIVE', 1, 'SEMANTIC', NULL, '[]', 1.0, 0, NULL, ?, ?, ?, 'it-embed',"
                + " ?::vector)")) {
      String now = java.time.Instant.now().toString();
      ps.setString(1, id);
      ps.setString(2, workspace);
      ps.setString(3, "pgvector isolation probe " + id);
      ps.setString(4, now);
      ps.setString(5, now);
      ps.setString(6, vector);
      ps.setString(7, vector);
      ps.executeUpdate();
    }
  }

  private static long scalar(Connection connection, String sql) throws Exception {
    try (var ps = connection.prepareStatement(sql);
        var rs = ps.executeQuery()) {
      return rs.next() ? rs.getLong(1) : 0;
    }
  }

  private static String text(Connection connection, String sql) throws Exception {
    try (var ps = connection.prepareStatement(sql);
        var rs = ps.executeQuery()) {
      return rs.next() ? rs.getString(1) : "";
    }
  }
}
