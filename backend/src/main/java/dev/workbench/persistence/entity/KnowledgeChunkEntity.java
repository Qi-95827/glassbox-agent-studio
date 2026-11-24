package dev.workbench.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 知识库分块与向量正文。
 *
 * <p>{@code content} 使用 TEXT/CLOB 保存原文片段；{@code embedding_json} 是跨库兼容的浮点数组， PostgreSQL Profile 另有
 * {@code embedding_vec vector(1536)} 与 HNSW 索引用于下推检索。
 */
@Entity
@Table(name = "knowledge_chunks")
public class KnowledgeChunkEntity {
  @Id
  @Column(name = "id", length = 36)
  private String id;

  @Column(name = "document_id", nullable = false, length = 36)
  private String documentId;

  @Column(name = "workspace_id", nullable = false, length = 36)
  private String workspaceId;

  @Column(name = "chunk_index", nullable = false)
  private int chunkIndex;

  @Column(name = "section_title", length = 300)
  private String sectionTitle;

  @JdbcTypeCode(SqlTypes.LONGVARCHAR)
  @Column(name = "content", nullable = false)
  private String content;

  @Column(name = "token_estimate", nullable = false)
  private int tokenEstimate;

  @JdbcTypeCode(SqlTypes.LONGVARCHAR)
  @Column(name = "embedding_json")
  private String embeddingJson;

  @Column(name = "embedding_model", length = 120)
  private String embeddingModel;

  @Column(name = "created_at", nullable = false, length = 40)
  private String createdAt;

  protected KnowledgeChunkEntity() {}

  public KnowledgeChunkEntity(
      String id,
      String documentId,
      String workspaceId,
      int chunkIndex,
      String sectionTitle,
      String content,
      int tokenEstimate,
      String embeddingJson,
      String embeddingModel,
      String createdAt) {
    this.id = id;
    this.documentId = documentId;
    this.workspaceId = workspaceId;
    this.chunkIndex = Math.max(0, chunkIndex);
    this.sectionTitle = sectionTitle;
    this.content = content == null ? "" : content;
    this.tokenEstimate = Math.max(0, tokenEstimate);
    this.embeddingJson = embeddingJson;
    this.embeddingModel = embeddingModel;
    this.createdAt = createdAt;
  }

  public String getId() {
    return id;
  }

  public String getDocumentId() {
    return documentId;
  }

  public String getWorkspaceId() {
    return workspaceId;
  }

  public int getChunkIndex() {
    return chunkIndex;
  }

  public String getSectionTitle() {
    return sectionTitle;
  }

  public String getContent() {
    return content;
  }

  public int getTokenEstimate() {
    return tokenEstimate;
  }

  public String getEmbeddingJson() {
    return embeddingJson;
  }

  public String getEmbeddingModel() {
    return embeddingModel;
  }

  public String getCreatedAt() {
    return createdAt;
  }

  public void embed(String embeddingJson, String embeddingModel) {
    this.embeddingJson = embeddingJson;
    this.embeddingModel = embeddingModel;
  }
}
