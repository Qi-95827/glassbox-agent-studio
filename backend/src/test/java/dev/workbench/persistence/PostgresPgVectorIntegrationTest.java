package dev.workbench.persistence;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * PostgreSQL + pgvector 集成验证（仅设置 {@code WORKBENCH_PG_TEST=true} 时执行）。
 *
 * <p>前置：{@code docker compose up -d} 并用 postgres Profile 启动过一次工作台，使
 * db/migration-pg 的基线、vector 扩展与 HNSW 索引就绪。
 */
@EnabledIfEnvironmentVariable(named = "WORKBENCH_PG_TEST", matches = "true")
class PostgresPgVectorIntegrationTest {
  private static final String URL =
      System.getenv().getOrDefault(
          "WORKBENCH_PG_URL", "jdbc:postgresql://127.0.0.1:5432/workbench");
  private static final String USER = System.getenv().getOrDefault("WORKBENCH_PG_USER", "workbench");
  private static final String PASSWORD =
      System.getenv().getOrDefault("WORKBENCH_PG_PASSWORD", "workbench");

  @Test
  void migrationsCreateVectorExtensionHnswAndWorkspaceIsolation() throws Exception {
    try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD)) {
      assertEquals(
          1,
          scalar(connection, "SELECT count(*) FROM pg_extension WHERE extname = 'vector'"),
          "pgvector 扩展必须已启用");
      assertTrue(
          text(connection, "SELECT indexdef FROM pg_indexes WHERE indexname = 'idx_memory_records_embedding_vec'")
              .toLowerCase(java.util.Locale.ROOT)
              .contains("hnsw"),
          "记忆向量必须使用 HNSW 索引");
      assertTrue(
          text(connection, "SELECT indexdef FROM pg_indexes WHERE indexname = 'idx_knowledge_chunks_embedding_vec'")
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
                "SELECT id FROM memory_records WHERE workspace_id = ? AND status = 'ACTIVE'"
                    + " AND embedding_vec IS NOT NULL ORDER BY embedding_vec <=> ?::vector LIMIT 5")) {
          ps.setString(1, workspaceA);
          ps.setString(2, vectorLiteral());
          try (var rs = ps.executeQuery()) {
            while (rs.next()) ids.add(rs.getString(1));
          }
        }
        assertEquals(List.of(idA), ids, "向量召回必须严格限定在当前工作区");
      } finally {
        try (var ps = connection.prepareStatement("DELETE FROM memory_records WHERE id IN (?, ?)")) {
          ps.setString(1, idA);
          ps.setString(2, idB);
          ps.executeUpdate();
        }
      }
    }
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
                + " last_hit_at, created_at, updated_at, embedding_json, embedding_model, embedding_vec)"
                + " VALUES (?, ?, 'PROJECT_CONVENTION', ?, 'USER_CONFIRMED', 'ACTIVE', 1, 'SEMANTIC',"
                + " NULL, '[]', 1.0, 0, NULL, ?, ?, ?, 'it-embed', ?::vector)")) {
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
