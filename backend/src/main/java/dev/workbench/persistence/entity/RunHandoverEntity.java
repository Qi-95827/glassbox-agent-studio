package dev.workbench.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

/**
 * 暂停运行的人工接手记录。每次接手新增一行；历史行保留，只有 ACTIVE 行可以交还。
 * evidence_invalidated 一旦在 RETURNED 行上为真，后续无改动接手不会清除重新验证要求。
 */
@Entity
@Table(name = "run_handovers")
public class RunHandoverEntity implements Persistable<String> {
  @Transient private boolean isNew = true;

  @Id
  @Column(name = "id", length = 36)
  private String id;

  @Column(name = "run_id", nullable = false, length = 36)
  private String runId;

  @Column(name = "workspace_id", nullable = false, length = 36)
  private String workspaceId;

  @Column(name = "status", nullable = false, length = 20)
  private String status;

  @Column(name = "baseline_fingerprint", nullable = false, length = 64)
  private String baselineFingerprint;

  @Column(name = "current_fingerprint", length = 64)
  private String currentFingerprint;

  @Column(name = "evidence_invalidated", nullable = false)
  private boolean evidenceInvalidated;

  @Column(name = "note", length = 1000)
  private String note;

  @Column(name = "taken_at", nullable = false, length = 40)
  private String takenAt;

  @Column(name = "returned_at", length = 40)
  private String returnedAt;

  protected RunHandoverEntity() {}

  public RunHandoverEntity(
      String id,
      String runId,
      String workspaceId,
      String status,
      String baselineFingerprint,
      String currentFingerprint,
      boolean evidenceInvalidated,
      String note,
      String takenAt,
      String returnedAt) {
    this.id = id;
    this.runId = runId;
    this.workspaceId = workspaceId;
    this.status = status;
    this.baselineFingerprint = baselineFingerprint;
    this.currentFingerprint = currentFingerprint;
    this.evidenceInvalidated = evidenceInvalidated;
    this.note = note;
    this.takenAt = takenAt;
    this.returnedAt = returnedAt;
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

  public String getRunId() {
    return runId;
  }

  public String getWorkspaceId() {
    return workspaceId;
  }

  public String getStatus() {
    return status;
  }

  public String getBaselineFingerprint() {
    return baselineFingerprint;
  }

  public String getCurrentFingerprint() {
    return currentFingerprint;
  }

  public boolean isEvidenceInvalidated() {
    return evidenceInvalidated;
  }

  public String getNote() {
    return note;
  }

  public String getTakenAt() {
    return takenAt;
  }

  public String getReturnedAt() {
    return returnedAt;
  }

  /** 交还时记录当前指纹和是否失效；说明为空时保留原说明。 */
  public void markReturned(
      String currentFingerprint,
      boolean evidenceInvalidated,
      String note,
      String returnedAt) {
    this.status = "RETURNED";
    this.currentFingerprint = currentFingerprint;
    this.evidenceInvalidated = evidenceInvalidated;
    if (note != null) this.note = note;
    this.returnedAt = returnedAt;
  }
}
