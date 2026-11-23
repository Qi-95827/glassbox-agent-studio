package dev.workbench.infrastructure.vector;

import dev.workbench.framework.memory.MemoryVectorIndex;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * PostgreSQL + pgvector 记忆向量索引。
 *
 * <p>只有当 JDBC 元数据确认为 PostgreSQL 时启用；H2 默认测试自动走内存精确余弦。
 * 所有 SQL 都带 {@code workspace_id = ?} 与 {@code status = 'ACTIVE'}，从数据库层面杜绝跨工作区
 * 与候选记忆泄露。
 */
@Component
public class JdbcMemoryVectorIndex implements MemoryVectorIndex {
  private final JdbcTemplate jdbc;
  private final DataSource dataSource;
  private volatile Boolean postgres;

  public JdbcMemoryVectorIndex(DataSource dataSource) {
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

  @Override
  public void store(String workspaceId, String memoryId, String modelName, float[] embedding) {
    if (!available() || embedding == null || embedding.length == 0) return;
    jdbc.update(
        "UPDATE memory_records SET embedding_vec = ?::vector, embedding_model = ?"
            + " WHERE id = ? AND workspace_id = ? AND status = 'ACTIVE'",
        literal(embedding),
        modelName,
        memoryId,
        workspaceId);
  }

  @Override
  public List<VectorHit> nearest(
      String workspaceId, String modelName, float[] query, int limit) {
    if (!available() || query == null || query.length == 0) return List.of();
    try {
      String vector = literal(query);
      var rows =
          jdbc.query(
              "SELECT id, 1 - (embedding_vec <=> ?::vector) AS cosine FROM memory_records"
                  + " WHERE workspace_id = ? AND status = 'ACTIVE' AND embedding_model = ?"
                  + " AND embedding_vec IS NOT NULL ORDER BY embedding_vec <=> ?::vector LIMIT ?",
              (rs, index) ->
                  new VectorHit(rs.getString("id"), clamp(rs.getDouble("cosine"))),
              vector,
              workspaceId,
              modelName,
              vector,
              Math.max(1, Math.min(100, limit)));
      return List.copyOf(rows);
    } catch (RuntimeException e) {
      // 扩展或列尚未就绪时退回内存余弦，不阻断召回。
      return List.of();
    }
  }

  /** pgvector 接受字符串字面量 {@code [0.1,0.2,...]} 的显式 text::vector 转换。 */
  static String literal(float[] embedding) {
    var text = new StringBuilder("[");
    for (int index = 0; index < embedding.length; index++) {
      if (index > 0) text.append(',');
      float value = embedding[index];
      text.append(Float.isFinite(value) ? value : 0f);
    }
    return text.append(']').toString();
  }

  private static double clamp(double value) {
    return Math.max(0, Math.min(1, value));
  }
}
