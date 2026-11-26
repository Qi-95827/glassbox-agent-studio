package dev.workbench.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 同任务一轮调改迭代的请求记录。
 *
 * <p>表以 {@code run_id} 为主键：一轮运行只对应一条调改请求，写入后不再更新；节点、模型调用、工具轨迹与交付记录 仍分别保存在各自的证据表中，源运行的任何行都不因迭代而改变。
 */
@Entity
@Table(name = "run_iterations")
public class RunIterationEntity {
  @Id
  @Column(name = "run_id", length = 36)
  private String runId;

  @Column(name = "task_id", nullable = false, length = 36)
  private String taskId;

  @Column(name = "parent_run_id", nullable = false, length = 36)
  private String parentRunId;

  @Column(name = "round_index", nullable = false)
  private int roundIndex;

  @Column(name = "target_node_key", length = 120)
  private String targetNodeKey;

  @Column(name = "model_mode", nullable = false, length = 20)
  private String modelMode;

  @Column(name = "model_connection_id", length = 64)
  private String modelConnectionId;

  @JdbcTypeCode(SqlTypes.LONGVARCHAR)
  @Column(name = "feedback", nullable = false)
  private String feedback;

  @JdbcTypeCode(SqlTypes.LONGVARCHAR)
  @Column(name = "previous_summary")
  private String previousSummary;

  @Column(name = "created_at", nullable = false, length = 40)
  private String createdAt;

  protected RunIterationEntity() {}

  public RunIterationEntity(
      String runId,
      String taskId,
      String parentRunId,
      int roundIndex,
      String targetNodeKey,
      String modelMode,
      String modelConnectionId,
      String feedback,
      String previousSummary,
      String createdAt) {
    this.runId = runId;
    this.taskId = taskId;
    this.parentRunId = parentRunId;
    this.roundIndex = roundIndex;
    this.targetNodeKey = targetNodeKey;
    this.modelMode = modelMode;
    this.modelConnectionId = modelConnectionId;
    this.feedback = feedback;
    this.previousSummary = previousSummary;
    this.createdAt = createdAt;
  }

  public String getRunId() {
    return runId;
  }

  public String getTaskId() {
    return taskId;
  }

  public String getParentRunId() {
    return parentRunId;
  }

  public int getRoundIndex() {
    return roundIndex;
  }

  public String getTargetNodeKey() {
    return targetNodeKey;
  }

  public String getModelMode() {
    return modelMode;
  }

  public String getModelConnectionId() {
    return modelConnectionId;
  }

  public String getFeedback() {
    return feedback;
  }

  public String getPreviousSummary() {
    return previousSummary;
  }

  public String getCreatedAt() {
    return createdAt;
  }
}
