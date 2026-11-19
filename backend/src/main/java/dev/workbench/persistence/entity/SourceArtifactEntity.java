package dev.workbench.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

/** 接入待确认任务的既有成果来源与版本证据。 */
@Entity
@Table(name = "task_source_artifacts")
public class SourceArtifactEntity {
  @Id
  @Column(name = "id", length = 36)
  private String id;

  @Column(name = "task_id", nullable = false, length = 36)
  private String taskId;

  @Column(name = "kind", nullable = false, length = 30)
  private String kind;

  @Column(name = "source_task_id", nullable = false, length = 36)
  private String sourceTaskId;

  @Column(name = "source_run_id", nullable = false, length = 36)
  private String sourceRunId;

  @Column(name = "source_task_version", nullable = false)
  private int sourceTaskVersion;

  @Column(name = "artifact_sha256", nullable = false, length = 64)
  private String artifactSha256;

  @Column(name = "summary", nullable = false, length = 500)
  private String summary;

  @Lob
  @Column(name = "content")
  private String content;

  @Lob
  @Column(name = "file_versions_json")
  private String fileVersionsJson;

  @Column(name = "created_at", nullable = false, length = 40)
  private String createdAt;

  protected SourceArtifactEntity() {}

  public SourceArtifactEntity(
      String id,
      String taskId,
      String kind,
      String sourceTaskId,
      String sourceRunId,
      int sourceTaskVersion,
      String artifactSha256,
      String summary,
      String content,
      String fileVersionsJson,
      String createdAt) {
    this.id = id;
    this.taskId = taskId;
    this.kind = kind;
    this.sourceTaskId = sourceTaskId;
    this.sourceRunId = sourceRunId;
    this.sourceTaskVersion = sourceTaskVersion;
    this.artifactSha256 = artifactSha256;
    this.summary = summary;
    this.content = content;
    this.fileVersionsJson = fileVersionsJson;
    this.createdAt = createdAt;
  }

  public String getId() {
    return id;
  }

  public String getTaskId() {
    return taskId;
  }

  public String getKind() {
    return kind;
  }

  public String getSourceTaskId() {
    return sourceTaskId;
  }

  public String getSourceRunId() {
    return sourceRunId;
  }

  public int getSourceTaskVersion() {
    return sourceTaskVersion;
  }

  public String getArtifactSha256() {
    return artifactSha256;
  }

  public String getSummary() {
    return summary;
  }

  public String getContent() {
    return content;
  }

  public String getFileVersionsJson() {
    return fileVersionsJson;
  }

  public String getCreatedAt() {
    return createdAt;
  }
}
