package dev.workbench.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 分支运行的来源记录：从哪一条运行、哪个节点或检查点分叉，以及这次分叉带入了哪些覆盖项。
 *
 * <p>只记录「分叉这件事」，不复制源运行的任何证据行：源运行的节点、模型调用、工具调用与检查点 仍按原 runId 保留，分支运行以自己的 runId 重新落库。
 */
@Entity
@Table(name = "run_forks")
public class RunForkEntity {
  @Id
  @Column(name = "run_id", length = 36, nullable = false)
  private String runId;

  @Column(name = "parent_run_id", length = 36, nullable = false)
  private String parentRunId;

  @Column(name = "source_node_key", length = 32)
  private String sourceNodeKey;

  @Column(name = "checkpoint_id", length = 36)
  private String checkpointId;

  @JdbcTypeCode(SqlTypes.LONGVARCHAR)
  @Column(name = "prompt_addendum")
  private String promptAddendum;

  @Column(name = "model_mode", length = 32, nullable = false)
  private String modelMode;

  @Column(name = "model_connection_id", length = 36)
  private String modelConnectionId;

  @Column(name = "created_at", length = 64, nullable = false)
  private String createdAt;

  protected RunForkEntity() {}

  public RunForkEntity(
      String runId,
      String parentRunId,
      String sourceNodeKey,
      String checkpointId,
      String promptAddendum,
      String modelMode,
      String modelConnectionId,
      String createdAt) {
    this.runId = runId;
    this.parentRunId = parentRunId;
    this.sourceNodeKey = sourceNodeKey;
    this.checkpointId = checkpointId;
    this.promptAddendum = promptAddendum;
    this.modelMode = modelMode;
    this.modelConnectionId = modelConnectionId;
    this.createdAt = createdAt;
  }

  public String getRunId() {
    return runId;
  }

  public String getParentRunId() {
    return parentRunId;
  }

  public String getSourceNodeKey() {
    return sourceNodeKey;
  }

  public String getCheckpointId() {
    return checkpointId;
  }

  public String getPromptAddendum() {
    return promptAddendum;
  }

  public String getModelMode() {
    return modelMode;
  }

  public String getModelConnectionId() {
    return modelConnectionId;
  }

  public String getCreatedAt() {
    return createdAt;
  }
}
