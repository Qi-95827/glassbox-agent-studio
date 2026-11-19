package dev.workbench.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** workflow_plans 表映射；确认与摘要更新仍由既有服务按状态条件执行。 */
@Entity
@Table(name = "workflow_plans")
public class WorkflowPlanEntity {
  @Id
  @Column(name = "id", length = 36)
  private String id;

  @Column(name = "run_id", nullable = false, length = 36)
  private String runId;

  @Column(name = "plan_version", nullable = false)
  private int planVersion;

  @Column(name = "template_id", nullable = false, length = 64)
  private String templateId;

  @Column(name = "template_version", nullable = false)
  private int templateVersion;

  @Column(name = "review_required", nullable = false)
  private boolean reviewRequired;

  @Column(name = "approved_at", length = 40)
  private String approvedAt;

  @Column(name = "graph_sha256", length = 64)
  private String graphSha256;

  @Column(name = "source_checkpoint_id", length = 36)
  private String sourceCheckpointId;

  @Column(name = "scope_type", nullable = false, length = 40)
  private String scopeType;

  @Column(name = "authorization_sha256", length = 64)
  private String authorizationSha256;

  @Column(name = "max_attempts", nullable = false)
  private int maxAttempts;

  protected WorkflowPlanEntity() {}

  public WorkflowPlanEntity(
      String id,
      String runId,
      int planVersion,
      String templateId,
      int templateVersion,
      boolean reviewRequired,
      String approvedAt,
      String scopeType,
      String authorizationSha256,
      int maxAttempts) {
    this.id = id;
    this.runId = runId;
    this.planVersion = planVersion;
    this.templateId = templateId;
    this.templateVersion = templateVersion;
    this.reviewRequired = reviewRequired;
    this.approvedAt = approvedAt;
    this.scopeType = scopeType;
    this.authorizationSha256 = authorizationSha256;
    this.maxAttempts = maxAttempts;
  }

  public String getId() {
    return id;
  }

  public String getRunId() {
    return runId;
  }

  public int getPlanVersion() {
    return planVersion;
  }

  public String getTemplateId() {
    return templateId;
  }

  public int getTemplateVersion() {
    return templateVersion;
  }

  public boolean isReviewRequired() {
    return reviewRequired;
  }

  public String getApprovedAt() {
    return approvedAt;
  }

  public String getGraphSha256() {
    return graphSha256;
  }

  public void setGraphSha256(String graphSha256) {
    this.graphSha256 = graphSha256;
  }

  public String getSourceCheckpointId() {
    return sourceCheckpointId;
  }

  public String getScopeType() {
    return scopeType;
  }

  public String getAuthorizationSha256() {
    return authorizationSha256;
  }

  public int getMaxAttempts() {
    return maxAttempts;
  }
}
