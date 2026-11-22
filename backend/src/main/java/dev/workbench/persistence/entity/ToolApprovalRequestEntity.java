package dev.workbench.persistence.entity;

import dev.workbench.framework.tool.ToolApprovalStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Gate 5.5 工具审批单；原始参数与修改后参数都保留，拒绝理由可回传模型。 */
@Entity
@Table(name = "tool_approval_requests")
public class ToolApprovalRequestEntity {
  @Id
  @Column(name = "id", length = 36, nullable = false)
  private String id;

  @Column(name = "run_id", length = 36, nullable = false)
  private String runId;

  @Column(name = "node_id", length = 36, nullable = false)
  private String nodeId;

  @Column(name = "agent_id", length = 36, nullable = false)
  private String agentId;

  @Column(name = "tool_name", length = 120, nullable = false)
  private String toolName;

  @Column(name = "tool_version", nullable = false)
  private int toolVersion;

  @Lob
  @Column(name = "original_args_json", nullable = false)
  private String originalArgsJson;

  @Lob
  @Column(name = "edited_args_json")
  private String editedArgsJson;

  @Column(name = "risk_level", length = 20, nullable = false)
  private String riskLevel;

  @Column(name = "risk_reason", length = 500, nullable = false)
  private String riskReason;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.VARCHAR)
  @Column(name = "status", length = 40, nullable = false)
  private ToolApprovalStatus status;

  @Column(name = "decision_action", length = 40)
  private String decisionAction;

  @Column(name = "decision_reason", length = 500)
  private String decisionReason;

  @Column(name = "requested_at", length = 40, nullable = false)
  private String requestedAt;

  @Column(name = "decided_at", length = 40)
  private String decidedAt;

  @Column(name = "expires_at", length = 40, nullable = false)
  private String expiresAt;

  protected ToolApprovalRequestEntity() {}

  public ToolApprovalRequestEntity(
      String id,
      String runId,
      String nodeId,
      String agentId,
      String toolName,
      int toolVersion,
      String originalArgsJson,
      String riskLevel,
      String riskReason,
      String requestedAt,
      String expiresAt) {
    this.id = id;
    this.runId = runId;
    this.nodeId = nodeId;
    this.agentId = agentId;
    this.toolName = toolName;
    this.toolVersion = toolVersion;
    this.originalArgsJson = originalArgsJson;
    this.riskLevel = riskLevel;
    this.riskReason = riskReason;
    this.status = ToolApprovalStatus.PENDING;
    this.requestedAt = requestedAt;
    this.expiresAt = expiresAt;
  }

  public void decide(
      ToolApprovalStatus status,
      String action,
      String editedArgsJson,
      String reason,
      String decidedAt) {
    this.status = status;
    this.decisionAction = action;
    this.editedArgsJson = editedArgsJson;
    this.decisionReason = reason;
    this.decidedAt = decidedAt;
  }

  public void expire(String decidedAt) {
    this.status = ToolApprovalStatus.EXPIRED;
    this.decisionAction = ToolApprovalStatus.EXPIRED.name();
    this.decisionReason = "审批超时，未执行高危工具。";
    this.decidedAt = decidedAt;
  }

  public String getId() {
    return id;
  }

  public String getRunId() {
    return runId;
  }

  public String getNodeId() {
    return nodeId;
  }

  public String getAgentId() {
    return agentId;
  }

  public String getToolName() {
    return toolName;
  }

  public int getToolVersion() {
    return toolVersion;
  }

  public String getOriginalArgsJson() {
    return originalArgsJson;
  }

  public String getEditedArgsJson() {
    return editedArgsJson;
  }

  public String getRiskLevel() {
    return riskLevel;
  }

  public String getRiskReason() {
    return riskReason;
  }

  public ToolApprovalStatus getStatus() {
    return status;
  }

  public String getDecisionAction() {
    return decisionAction;
  }

  public String getDecisionReason() {
    return decisionReason;
  }

  public String getRequestedAt() {
    return requestedAt;
  }

  public String getDecidedAt() {
    return decidedAt;
  }

  public String getExpiresAt() {
    return expiresAt;
  }
}
