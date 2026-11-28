package dev.workbench.infrastructure.vector;

import dev.workbench.framework.memory.MemoryVectorIndex;
import java.util.List;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * PostgreSQL + pgvector 记忆向量索引。
 *
 * <p>只有当 JDBC 元数据确认为 PostgreSQL 时启用；H2 默认测试自动走内存精确余弦。 所有 SQL 都带 {@code workspace_id = ?} 与 {@code
 * status = 'ACTIVE'}，从数据库层面杜绝跨工作区 与候选记忆泄露。
 *
 * <p>向量列固定为基线声明的维度（当前 {@code vector(1536)}）。模型维度不一致时写入会回滚外层事务， 因此先探测声明维度：不一致或数据库拒绝时只记 WARN
 * 并跳过向量列，记忆正文与 {@code embedding_json} 仍然落库。召回查询可按配置放宽 HNSW 候选池。
 */
@Component
public class JdbcMemoryVectorIndex implements MemoryVectorIndex {
  private static final Logger LOG = LoggerFactory.getLogger(JdbcMemoryVectorIndex.class);
  private static final String TABLE = "memory_records";
  private static final String VECTOR_COLUMN = "embedding_vec";

  private final JdbcTemplate jdbc;
  private final DataSource dataSource;
  private final int efSearch;
  private volatile Boolean postgres;
  private volatile int declaredDimension;

  public JdbcMemoryVectorIndex(
      DataSource dataSource, @Value("${workbench.vector.hnsw.ef-search:100}") int efSearch) {
    this.dataSource = dataSource;
    this.jdbc = new JdbcTemplate(dataSource);
    this.efSearch = efSearch;
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
    int dimension = columnDimension();
    if (dimension > 0 && dimension != embedding.length) {
      LOG.warn(
          "记忆向量维度与数据库列声明不一致，跳过向量列写入：期望 {}，实际 {}，模型 {}", dimension, embedding.length, modelName);
      return;
    }
    try {
      jdbc.update(
          "UPDATE memory_records SET embedding_vec = ?::vector, embedding_model = ?"
              + " WHERE id = ? AND workspace_id = ? AND status = 'ACTIVE'",
          literal(embedding),
          modelName,
          memoryId,
          workspaceId);
    } catch (RuntimeException e) {
      // 失败不能回滚外层记忆回填事务；embedding_json 仍可支撑内存余弦降级。
      LOG.warn("记忆向量列写入失败，已保留 embedding_json 并降级为词法召回：{}", e.getMessage());
    }
  }

  /**
   * 清空单条记忆的向量列与向量模型名。
   *
   * <p>正文被修改后旧向量必然过期，必须显式置空；否则 PG 会继续用旧向量命中该记忆。失败只记 WARN：
   * 清理失败不应回滚用户已经成功的正文修改，下一次回填会覆盖该列。
   */
  @Override
  public void clear(String workspaceId, String memoryId) {
    if (!available()) return;
    try {
      jdbc.update(
          "UPDATE memory_records SET embedding_vec = NULL, embedding_model = NULL"
              + " WHERE id = ? AND workspace_id = ?",
          memoryId,
          workspaceId);
    } catch (RuntimeException e) {
      LOG.warn("记忆向量列清理失败，旧向量可能短期残留：{}", e.getMessage());
    }
  }

  private int columnDimension() {
    int cached = declaredDimension;
    if (cached > 0) return cached;
    synchronized (this) {
      if (declaredDimension <= 0)
        declaredDimension = PgVectorSupport.declaredDimension(jdbc, TABLE, VECTOR_COLUMN);
      return declaredDimension;
    }
  }

  @Override
  @Transactional(readOnly = true)
  public List<VectorHit> nearest(String workspaceId, String modelName, float[] query, int limit) {
    if (!available() || query == null || query.length == 0) return List.of();
    try {
      PgVectorSupport.applyEfSearch(jdbc, efSearch);
      String vector = literal(query);
      var rows =
          jdbc.query(
              "SELECT id, 1 - (embedding_vec <=> ?::vector) AS cosine FROM memory_records"
                  + " WHERE workspace_id = ? AND status = 'ACTIVE' AND embedding_model = ?"
                  + " AND embedding_vec IS NOT NULL ORDER BY embedding_vec <=> ?::vector LIMIT ?",
              (rs, index) -> new VectorHit(rs.getString("id"), clamp(rs.getDouble("cosine"))),
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
