package dev.workbench.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

/** 一次任务创建实际采用的规则路由与解释。 */
@Entity
@Table(name = "task_entry_routes")
public class EntryRouteEntity {
  @Id
  @Column(name = "task_id", length = 36)
  private String taskId;

  @Column(name = "rule_code", nullable = false, length = 80)
  private String ruleCode;

  @Column(name = "requested_kind", length = 30)
  private String requestedKind;

  @Column(name = "routed_kind", nullable = false, length = 30)
  private String routedKind;

  @Column(name = "template_id", length = 64)
  private String templateId;

  @Column(name = "template_version")
  private Integer templateVersion;

  @Lob
  @Column(name = "explanation_json", nullable = false)
  private String explanationJson;

  @Column(name = "decision_sha256", nullable = false, length = 64)
  private String decisionSha256;

  @Column(name = "created_at", nullable = false, length = 40)
  private String createdAt;

  protected EntryRouteEntity() {}

  public EntryRouteEntity(
      String taskId,
      String ruleCode,
      String requestedKind,
      String routedKind,
      String templateId,
      Integer templateVersion,
      String explanationJson,
      String decisionSha256,
      String createdAt) {
    this.taskId = taskId;
    this.ruleCode = ruleCode;
    this.requestedKind = requestedKind;
    this.routedKind = routedKind;
    this.templateId = templateId;
    this.templateVersion = templateVersion;
    this.explanationJson = explanationJson;
    this.decisionSha256 = decisionSha256;
    this.createdAt = createdAt;
  }

  public String getTaskId() {
    return taskId;
  }

  public String getRuleCode() {
    return ruleCode;
  }

  public String getRequestedKind() {
    return requestedKind;
  }

  public String getRoutedKind() {
    return routedKind;
  }

  public String getTemplateId() {
    return templateId;
  }

  public Integer getTemplateVersion() {
    return templateVersion;
  }

  public String getExplanationJson() {
    return explanationJson;
  }

  public String getDecisionSha256() {
    return decisionSha256;
  }

  public String getCreatedAt() {
    return createdAt;
  }
}
