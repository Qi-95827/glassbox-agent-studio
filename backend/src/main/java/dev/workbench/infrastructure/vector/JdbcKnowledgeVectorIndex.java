package dev.workbench.infrastructure.vector;

import dev.workbench.framework.memory.KnowledgeVectorIndex;
import java.util.List;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * PostgreSQL + pgvector 知识库分块索引。
 *
 * <p>H2 默认测试没有 pgvector，返回空结果，业务层回退为内存精确余弦。SQL 强制 {@code workspace_id = ?}，从数据库层面保证工作区隔离。
 *
 * <p>向量列固定为基线声明的维度（当前 {@code vector(1536)}）。当端点模型维度与之不同时，写入会因类型 不匹配回滚整笔入库事务，因此这里先探测声明维度：不一致时记 WARN
 * 并只保留 {@code embedding_json}， 让文档正常入库、检索自动降级为词法召回。召回查询可按配置放宽 HNSW 候选池，缓解小工作区过筛。
 */
@Component
public class JdbcKnowledgeVectorIndex implements KnowledgeVectorIndex {
  private static final Logger LOG = LoggerFactory.getLogger(JdbcKnowledgeVectorIndex.class);
  private static final String TABLE = "knowledge_chunks";
  private static final String VECTOR_COLUMN = "embedding_vec";

  private final JdbcTemplate jdbc;
  private final DataSource dataSource;
  private final int efSearch;
  private volatile Boolean postgres;
  private volatile int declaredDimension;

  public JdbcKnowledgeVectorIndex(
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

  /** 写入向量列；维度与列声明不一致或数据库拒绝时只记录告警，绝不向上抛异常阻断文档入库。 */
  public void store(String workspaceId, String chunkId, String modelName, float[] embedding) {
    if (!available() || embedding == null || embedding.length == 0) return;
    int dimension = columnDimension();
    if (dimension > 0 && dimension != embedding.length) {
      LOG.warn(
          "知识库向量维度与数据库列声明不一致，跳过向量列写入：期望 {}，实际 {}，模型 {}", dimension, embedding.length, modelName);
      return;
    }
    try {
      jdbc.update(
          "UPDATE knowledge_chunks SET embedding_vec = ?::vector, embedding_model = ?"
              + " WHERE id = ? AND workspace_id = ?",
          JdbcMemoryVectorIndex.literal(embedding),
          modelName,
          chunkId,
          workspaceId);
    } catch (RuntimeException e) {
      // 写入失败不能让外层文档入库事务回滚；embedding_json 仍可支撑内存余弦降级。
      LOG.warn("知识库向量列写入失败，已保留 embedding_json 并降级为词法召回：{}", e.getMessage());
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

  /**
   * 批量写入整篇文档的分块向量：一次 batchUpdate 完成，维度不一致时整体跳过。
   *
   * <p>逐条 UPDATE 会让一篇长文档产生数百次数据库往返；批量执行同时把「维度不一致即整体降级」的判定 收敛到一次，避免部分成功造成检索结果不一致。
   */
  @Override
  public void storeAll(
      String workspaceId, String modelName, java.util.Map<String, float[]> vectors) {
    if (!available() || vectors == null || vectors.isEmpty()) return;
    int dimension = columnDimension();
    if (dimension > 0) {
      var mismatch =
          vectors.values().stream()
              .filter(vector -> vector != null && vector.length > 0 && vector.length != dimension)
              .findFirst();
      if (mismatch.isPresent()) {
        LOG.warn(
            "知识库向量维度与数据库列声明不一致，跳过整篇文档的向量列写入：期望 {}，实际 {}，模型 {}",
            dimension,
            mismatch.get().length,
            modelName);
        return;
      }
    }
    var entries =
        vectors.entrySet().stream()
            .filter(entry -> entry.getValue() != null && entry.getValue().length > 0)
            .toList();
    if (entries.isEmpty()) return;
    try {
      jdbc.batchUpdate(
          "UPDATE knowledge_chunks SET embedding_vec = ?::vector, embedding_model = ?"
              + " WHERE id = ? AND workspace_id = ?",
          entries.stream()
              .map(
                  entry ->
                      new Object[] {
                        JdbcMemoryVectorIndex.literal(entry.getValue()),
                        modelName,
                        entry.getKey(),
                        workspaceId
                      })
              .toList());
    } catch (RuntimeException e) {
      LOG.warn("知识库向量列批量写入失败，已保留 embedding_json 并降级为词法召回：{}", e.getMessage());
    }
  }

  @Override
  @Transactional(readOnly = true)
  public List<ChunkHit> nearest(String workspaceId, String modelName, float[] query, int limit) {
    if (!available() || query == null || query.length == 0) return List.of();
    try {
      // SET LOCAL 只在当前事务内生效，不污染连接池中的连接。
      PgVectorSupport.applyEfSearch(jdbc, efSearch);
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
