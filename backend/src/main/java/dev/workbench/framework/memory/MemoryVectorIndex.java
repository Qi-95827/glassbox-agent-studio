package dev.workbench.framework.memory;

import java.util.List;

/**
 * 向量索引下推端口（PostgreSQL + pgvector）。
 *
 * <p>业务层只依赖本接口，不感知具体数据库。H2 模式没有实现，返回 {@code available() == false}，
 * 由调用方在内存中做精确余弦计算。所有查询必须携带 {@code workspaceId}，不得跨工作区召回。
 */
public interface MemoryVectorIndex {
  boolean available();

  /** 写入/覆盖单条 ACTIVE 记忆的向量；禁止写入 CANDIDATE。 */
  void store(String workspaceId, String memoryId, String modelName, float[] embedding);

  /**
   * 按正文版本条件写入单条 ACTIVE 记忆的向量；版本已推进时静默丢弃旧回填，绝不覆盖新正文。
   *
   * <p>默认实现委托给 {@link #store}，PostgreSQL 实现需在 SQL 中显式校验 {@code version = ?}。
   */
  default void storeIfVersion(
      String workspaceId, String memoryId, String modelName, float[] embedding, int expectedVersion) {
    store(workspaceId, memoryId, modelName, embedding);
  }

  /** 按工作区与向量模型召回最近的 Top-K；cosine 范围为 [0,1]。 */
  List<VectorHit> nearest(String workspaceId, String modelName, float[] query, int limit);

  /**
   * 清除单条记忆的持久化向量列；记忆正文变化或删除后必须调用，避免旧向量继续参与召回。
   *
   * <p>默认空实现：H2 或未启用向量索引的环境没有独立向量列，清理是幂等的无操作。
   */
  default void clear(String workspaceId, String memoryId) {}

  record VectorHit(String memoryId, double cosine) {}
}
