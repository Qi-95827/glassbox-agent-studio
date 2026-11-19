package dev.workbench.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

/** Bug 检测记录；逐用例结果单独存在 {@code bug_check_cases}。 */
@Entity
@Table(name = "bug_checks")
public class BugCheckEntity implements Persistable<String> {
  @Transient private boolean isNew = true;

  @Id
  @Column(name = "id", length = 36, nullable = false)
  private String id;

  @Column(name = "bug_id", length = 80, nullable = false)
  private String bugId;

  @Column(name = "workspace_id", length = 36, nullable = false)
  private String workspaceId;

  @Column(name = "run_id", length = 36)
  private String runId;

  @Column(name = "kind", length = 30, nullable = false)
  private String kind;

  @Column(name = "status", length = 30, nullable = false)
  private String status;

  @Lob
  @Column(name = "output")
  private String output;

  @Column(name = "duration_ms", nullable = false)
  private long durationMs;

  @Column(name = "created_at", length = 40, nullable = false)
  private String createdAt;

  @Column(name = "workspace_fingerprint", length = 64)
  private String workspaceFingerprint;

  public BugCheckEntity() {}

  public BugCheckEntity(
      String id,
      String bugId,
      String workspaceId,
      String runId,
      String kind,
      String status,
      String output,
      long durationMs,
      String createdAt,
      String workspaceFingerprint) {
    this.id = id;
    this.bugId = bugId;
    this.workspaceId = workspaceId;
    this.runId = runId;
    this.kind = kind;
    this.status = status;
    this.output = output;
    this.durationMs = durationMs;
    this.createdAt = createdAt;
    this.workspaceFingerprint = workspaceFingerprint;
  }

  @Override
  public String getId() {
    return id;
  }

  @Override
  public boolean isNew() {
    return isNew;
  }

  @PostLoad
  @PostPersist
  void markNotNew() {
    isNew = false;
  }

  public String getBugId() {
    return bugId;
  }

  public String getWorkspaceId() {
    return workspaceId;
  }

  public String getRunId() {
    return runId;
  }

  public String getKind() {
    return kind;
  }

  public String getStatus() {
    return status;
  }

  public String getOutput() {
    return output;
  }

  public long getDurationMs() {
    return durationMs;
  }

  public String getCreatedAt() {
    return createdAt;
  }

  public String getWorkspaceFingerprint() {
    return workspaceFingerprint;
  }
}
