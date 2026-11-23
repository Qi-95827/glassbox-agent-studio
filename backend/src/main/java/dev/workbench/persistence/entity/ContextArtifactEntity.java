package dev.workbench.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

/**
 * L1 上下文卸载产物：完整正文落盘，消息体内只保留 ArtifactRef 与高相关摘录。
 *
 * <p>{@code outline_json} 保存标题/函数签名/文件路径/异常行等结构化大纲，便于按需回查而无需重跑工具。
 */
@Entity
@Table(name = "context_artifacts")
public class ContextArtifactEntity {
  @Id
  @Column(name = "id", length = 48)
  private String id;

  @Column(name = "run_id", length = 36)
  private String runId;

  @Column(name = "tool_call_id", length = 120)
  private String toolCallId;

  @Column(name = "title", nullable = false, length = 300)
  private String title;

  @Lob
  @Column(name = "content", nullable = false)
  private String content;

  @Column(name = "char_count", nullable = false)
  private int charCount;

  @Lob
  @Column(name = "outline_json")
  private String outlineJson;

  @Column(name = "created_at", nullable = false, length = 40)
  private String createdAt;

  protected ContextArtifactEntity() {}

  public ContextArtifactEntity(
      String id,
      String runId,
      String toolCallId,
      String title,
      String content,
      String outlineJson,
      String createdAt) {
    this.id = id;
    this.runId = runId;
    this.toolCallId = toolCallId;
    this.title = title;
    this.content = content == null ? "" : content;
    this.charCount = this.content.length();
    this.outlineJson = outlineJson;
    this.createdAt = createdAt;
  }

  public String getId() {
    return id;
  }

  public String getRunId() {
    return runId;
  }

  public String getToolCallId() {
    return toolCallId;
  }

  public String getTitle() {
    return title;
  }

  public String getContent() {
    return content;
  }

  public int getCharCount() {
    return charCount;
  }

  public String getOutlineJson() {
    return outlineJson;
  }

  public String getCreatedAt() {
    return createdAt;
  }
}
