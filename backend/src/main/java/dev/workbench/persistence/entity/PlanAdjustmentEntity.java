package dev.workbench.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 待确认计划的一次候选调整；应用后保留历史版本供差异查看。 */
@Entity
@Table(name = "plan_adjustments")
public class PlanAdjustmentEntity {
  @Id
  @Column(name = "id", length = 36)
  private String id;

  @Column(name = "run_id", nullable = false, length = 36)
  private String runId;

  @Column(name = "plan_version_id", nullable = false, length = 36)
  private String planVersionId;

  @Column(name = "version", nullable = false)
  private int version;

  @Column(name = "base_sha256", nullable = false, length = 64)
  private String baseSha256;

  @JdbcTypeCode(SqlTypes.LONGVARCHAR)
  @Column(name = "proposal_json", nullable = false)
  private String proposalJson;

  @Column(name = "proposal_sha256", nullable = false, length = 64)
  private String proposalSha256;

  @Column(name = "status", nullable = false, length = 20)
  private String status;

  @Column(name = "created_at", nullable = false, length = 40)
  private String createdAt;

  @Column(name = "applied_at", length = 40)
  private String appliedAt;

  protected PlanAdjustmentEntity() {}

  public PlanAdjustmentEntity(
      String id,
      String runId,
      String planVersionId,
      int version,
      String baseSha256,
      String proposalJson,
      String proposalSha256,
      String status,
      String createdAt) {
    this.id = id;
    this.runId = runId;
    this.planVersionId = planVersionId;
    this.version = version;
    this.baseSha256 = baseSha256;
    this.proposalJson = proposalJson;
    this.proposalSha256 = proposalSha256;
    this.status = status;
    this.createdAt = createdAt;
  }

  public String getId() {
    return id;
  }

  public String getRunId() {
    return runId;
  }

  public String getPlanVersionId() {
    return planVersionId;
  }

  public int getVersion() {
    return version;
  }

  public String getBaseSha256() {
    return baseSha256;
  }

  public String getProposalJson() {
    return proposalJson;
  }

  public String getProposalSha256() {
    return proposalSha256;
  }

  public String getStatus() {
    return status;
  }

  public String getCreatedAt() {
    return createdAt;
  }

  public String getAppliedAt() {
    return appliedAt;
  }

  public void apply(String time) {
    this.status = "APPLIED";
    this.appliedAt = time;
  }
}
