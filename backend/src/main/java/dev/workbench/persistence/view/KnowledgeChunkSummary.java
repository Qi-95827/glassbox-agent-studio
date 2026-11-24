package dev.workbench.persistence.view;

/**
 * 知识库分块的轻量投影：检索只需要编号、正文与标题，绝不把 {@code embedding_json}（约 20KB/条）拉进堆。
 *
 * <p>向量重排只对最终候选集按需回表读取 embedding，避免对整库做反序列化。
 */
public interface KnowledgeChunkSummary {
  String getId();

  String getDocumentId();

  String getSectionTitle();

  String getContent();

  int getTokenEstimate();
}
