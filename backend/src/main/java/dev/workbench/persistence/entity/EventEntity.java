package dev.workbench.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** events 表的映射。运行内序号 run_seq 由锁定运行行后分配。 */
@Entity
@Table(name = "events")
public class EventEntity {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "id")
  private Long id;

  @Column(name = "run_seq")
  private Long runSeq;

  @Column(name = "run_id", nullable = false, length = 36)
  private String runId;

  @Column(name = "event_type", nullable = false, length = 50)
  private String eventType;

  @Column(name = "message", nullable = false, length = 2000)
  private String message;

  @Column(name = "created_at", nullable = false, length = 40)
  private String createdAt;

  @Column(name = "reason_code", length = 50)
  private String reasonCode;

  @Column(name = "plan_version_id", length = 36)
  private String planVersionId;

  @Column(name = "node_id", length = 36)
  private String nodeId;

  @Column(name = "attempt_id", length = 36)
  private String attemptId;

  @Column(name = "agent_id", length = 36)
  private String agentId;

  @Column(name = "check_id", length = 36)
  private String checkId;

  @Column(name = "model_call_id", length = 36)
  private String modelCallId;

  @Column(name = "tool_call_id", length = 36)
  private String toolCallId;

  protected EventEntity() {}

  public EventEntity(
      Long runSeq,
      String runId,
      String eventType,
      String message,
      String createdAt,
      String reasonCode,
      String planVersionId,
      String nodeId,
      String attemptId,
      String agentId,
      String checkId,
      String modelCallId,
      String toolCallId) {
    this.runSeq = runSeq;
    this.runId = runId;
    this.eventType = eventType;
    this.message = message;
    this.createdAt = createdAt;
    this.reasonCode = reasonCode;
    this.planVersionId = planVersionId;
    this.nodeId = nodeId;
    this.attemptId = attemptId;
    this.agentId = agentId;
    this.checkId = checkId;
    this.modelCallId = modelCallId;
    this.toolCallId = toolCallId;
  }

  public Long getId() {
    return id;
  }

  public Long getRunSeq() {
    return runSeq;
  }

  public String getRunId() {
    return runId;
  }

  public String getEventType() {
    return eventType;
  }

  public String getMessage() {
    return message;
  }

  public String getCreatedAt() {
    return createdAt;
  }

  public String getReasonCode() {
    return reasonCode;
  }

  public String getPlanVersionId() {
    return planVersionId;
  }

  public String getNodeId() {
    return nodeId;
  }

  public String getAttemptId() {
    return attemptId;
  }

  public String getAgentId() {
    return agentId;
  }

  public String getCheckId() {
    return checkId;
  }

  public String getModelCallId() {
    return modelCallId;
  }

  public String getToolCallId() {
    return toolCallId;
  }
}
