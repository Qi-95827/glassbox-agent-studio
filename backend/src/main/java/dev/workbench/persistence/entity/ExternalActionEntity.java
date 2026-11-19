package dev.workbench.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 外部写动作的审批绑定、目标版本与对账证据。 */
@Entity
@Table(name = "external_actions")
public class ExternalActionEntity {
  @Id
  @Column(name = "id", length = 36)
  private String id;

  @Column(name = "run_id", length = 36)
  private String runId;

  @Column(name = "action_key", nullable = false, length = 120)
  private String actionKey;

  @Column(name = "kind", nullable = false, length = 40)
  private String kind;

  @Column(name = "status", nullable = false, length = 30)
  private String status;

  @Column(name = "approval_ref", length = 80)
  private String approvalRef;

  @Column(name = "target", nullable = false, length = 300)
  private String target;

  @Column(name = "target_version", length = 100)
  private String targetVersion;

  @Column(name = "payload_sha256", nullable = false, length = 64)
  private String payloadSha256;

  @Column(name = "result_evidence", length = 2000)
  private String resultEvidence;

  @Column(name = "attempts", nullable = false)
  private int attempts;

  @Column(name = "last_checked_at", length = 40)
  private String lastCheckedAt;

  @Column(name = "created_at", nullable = false, length = 40)
  private String createdAt;

  @Column(name = "updated_at", nullable = false, length = 40)
  private String updatedAt;

  protected ExternalActionEntity() {}

  public ExternalActionEntity(
      String id,
      String runId,
      String actionKey,
      String kind,
      String status,
      String approvalRef,
      String target,
      String targetVersion,
      String payloadSha256,
      String createdAt,
      String updatedAt) {
    this.id = id;
    this.runId = runId;
    this.actionKey = actionKey;
    this.kind = kind;
    this.status = status;
    this.approvalRef = approvalRef;
    this.target = target;
    this.targetVersion = targetVersion;
    this.payloadSha256 = payloadSha256;
    this.attempts = 0;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
  }

  public String getId() {
    return id;
  }

  public String getRunId() {
    return runId;
  }

  public String getActionKey() {
    return actionKey;
  }

  public String getKind() {
    return kind;
  }

  public String getStatus() {
    return status;
  }

  public String getApprovalRef() {
    return approvalRef;
  }

  public String getTarget() {
    return target;
  }

  public String getTargetVersion() {
    return targetVersion;
  }

  public String getPayloadSha256() {
    return payloadSha256;
  }

  public String getResultEvidence() {
    return resultEvidence;
  }

  public int getAttempts() {
    return attempts;
  }

  public String getLastCheckedAt() {
    return lastCheckedAt;
  }

  public String getCreatedAt() {
    return createdAt;
  }

  public String getUpdatedAt() {
    return updatedAt;
  }

  public void markDispatched(String time) {
    this.status = "DISPATCHED";
    this.attempts++;
    this.updatedAt = time;
  }

  public void applyReconciliation(String result, String evidence, String time) {
    this.status = result;
    this.resultEvidence = evidence;
    this.lastCheckedAt = time;
    this.updatedAt = time;
  }

  public void markUnknown(String evidence, String time) {
    this.status = "NEEDS_MANUAL_REVIEW";
    this.resultEvidence = evidence;
    this.lastCheckedAt = time;
    this.updatedAt = time;
  }
}
