package dev.workbench.infrastructure.vector;

import dev.workbench.framework.memory.KnowledgeVectorIndex;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * PostgreSQL + pgvector 知识库分块索引。
 *
 * <p>H2 默认测试没有 pgvector，返回空结果，业务层回退为内存精确余弦。SQL 强制
 * {@code workspace_id = ?}，从数据库层面保证工作区隔离。
 */
@Component
public class JdbcKnowledgeVectorIndex implements KnowledgeVectorIndex {
  private final JdbcTemplate jdbc;
  private final DataSource dataSource;
  private volatile Boolean postgres;

  public JdbcKnowledgeVectorIndex(DataSource dataSource) {
    this.dataSource = dataSource;
    this.jdbc = new JdbcTemplate(dataSource);
  }

  @Override
  public boolean available() {
    if (postgres != null) return postgres;
    synchronized (this) {
      if (postgres == null) postgres = detectPostgres();
      return postgres;
    }
  }

  private boolean detectPostgres() {
    try (var connection = dataSource.getConnection()) {
      String product = connection.getMetaData().getDatabaseProductName();
      return product != null && product.toLowerCase(java.util.Locale.ROOT).contains("postgresql");
    } catch (Exception e) {
      return false;
    }
  }

  public void store(String workspaceId, String chunkId, String modelName, float[] embedding) {
    if (!available() || embedding == null || embedding.length == 0) return;
    jdbc.update(
        "UPDATE knowledge_chunks SET embedding_vec = ?::vector, embedding_model = ?"
            + " WHERE id = ? AND workspace_id = ?",
        JdbcMemoryVectorIndex.literal(embedding),
        modelName,
        chunkId,
        workspaceId);
  }

  @Override
  public List<ChunkHit> nearest(
      String workspaceId, String modelName, float[] query, int limit) {
    if (!available() || query == null || query.length == 0) return List.of();
    try {
      String vector = JdbcMemoryVectorIndex.literal(query);
      var rows =
          jdbc.query(
              "SELECT id, 1 - (embedding_vec <=> ?::vector) AS cosine FROM knowledge_chunks"
                  + " WHERE workspace_id = ? AND embedding_model = ? AND embedding_vec IS NOT NULL"
                  + " ORDER BY embedding_vec <=> ?::vector LIMIT ?",
              (rs, index) -> new ChunkHit(rs.getString("id"), clamp(rs.getDouble("cosine"))),
              vector,
              workspaceId,
              modelName,
              vector,
              Math.max(1, Math.min(200, limit)));
      return List.copyOf(rows);
    } catch (RuntimeException e) {
      return List.of();
    }
  }

  private static double clamp(double value) {
    return Math.max(0, Math.min(1, value));
  }
}
