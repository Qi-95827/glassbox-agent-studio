package dev.workbench.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 工作区知识库文档。
 *
 * <p>以 {@code workspace_id + source_path + content_sha256} 去重；工作区归属是不可省略的检索边界，
 * 禁止跨工作区统计或召回。本文档不保存用户 API Key 或运行私密数据。
 */
@Entity
@Table(name = "knowledge_documents")
public class KnowledgeDocumentEntity {
  @Id
  @Column(name = "id", length = 36)
  private String id;

  @Column(name = "workspace_id", nullable = false, length = 36)
  private String workspaceId;

  @Column(name = "owner_scope_key", nullable = false, length = 80)
  private String ownerScopeKey;

  @Column(name = "source_type", nullable = false, length = 40)
  private String sourceType;

  @Column(name = "source_path", length = 1000)
  private String sourcePath;

  @Column(name = "title", nullable = false, length = 300)
  private String title;

  @Column(name = "content_sha256", nullable = false, length = 64)
  private String contentSha256;

  @Column(name = "chunk_count", nullable = false)
  private int chunkCount;

  @Column(name = "status", nullable = false, length = 30)
  private String status;

  @Column(name = "created_at", nullable = false, length = 40)
  private String createdAt;

  @Column(name = "updated_at", nullable = false, length = 40)
  private String updatedAt;

  protected KnowledgeDocumentEntity() {}

  public KnowledgeDocumentEntity(
      String id,
      String workspaceId,
      String ownerScopeKey,
      String sourceType,
      String sourcePath,
      String title,
      String contentSha256,
      int chunkCount,
      String status,
      String createdAt,
      String updatedAt) {
    this.id = id;
    this.workspaceId = workspaceId;
    this.ownerScopeKey = ownerScopeKey;
    this.sourceType = sourceType;
    this.sourcePath = sourcePath;
    this.title = title;
    this.contentSha256 = contentSha256;
    this.chunkCount = Math.max(0, chunkCount);
    this.status = status;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
  }

  public String getId() {
    return id;
  }

  public String getWorkspaceId() {
    return workspaceId;
  }

  public String getOwnerScopeKey() {
    return ownerScopeKey;
  }

  public String getSourceType() {
    return sourceType;
  }

  public String getSourcePath() {
    return sourcePath;
  }

  public String getTitle() {
    return title;
  }

  public String getContentSha256() {
    return contentSha256;
  }

  public int getChunkCount() {
    return chunkCount;
  }

  public String getStatus() {
    return status;
  }

  public String getCreatedAt() {
    return createdAt;
  }

  public String getUpdatedAt() {
    return updatedAt;
  }

  public void reindexed(int chunkCount, String updatedAt) {
    this.chunkCount = Math.max(0, chunkCount);
    this.status = "INDEXED";
    this.updatedAt = updatedAt;
  }
}
