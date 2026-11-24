package dev.workbench.framework.memory;

import java.util.List;

/**
 * 知识库分块向量下推端口（PostgreSQL + pgvector）。
 *
 * <p>按工作区过滤，禁止跨工作区召回；H2 没有实现时由业务层在内存中精确计算余弦。
 */
public interface KnowledgeVectorIndex {
  boolean available();

  /** 写入/覆盖分块向量；H2 实现可保持空操作。 */
  default void store(String workspaceId, String chunkId, String modelName, float[] embedding) {}

  /**
   * 批量写入同一模型的向量。
   *
   * <p>默认逐条调用；PostgreSQL 实现应覆盖为单次批量执行，避免整篇文档逐条往返数据库。
   *
   * @param vectors 分块标识到向量的有序映射
   */
  default void storeAll(
      String workspaceId, String modelName, java.util.Map<String, float[]> vectors) {
    vectors.forEach((chunkId, embedding) -> store(workspaceId, chunkId, modelName, embedding));
  }

  List<ChunkHit> nearest(String workspaceId, String modelName, float[] query, int limit);

  record ChunkHit(String chunkId, double cosine) {}
}
