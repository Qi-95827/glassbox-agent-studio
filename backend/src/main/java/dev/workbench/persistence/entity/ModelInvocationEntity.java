package dev.workbench.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "model_invocations")
public class ModelInvocationEntity extends InvocationEntity {
  @Column(name = "agent_id", length = 36, nullable = false)
  private String agentId;

  @Column(name = "model_name", length = 80, nullable = false)
  private String modelName;

  @Column(name = "input_tokens")
  private Integer inputTokens;

  @Column(name = "output_tokens")
  private Integer outputTokens;

  @Column(name = "duration_ms")
  private Long durationMs;

  @Column(name = "error_message", length = 1000)
  private String errorMessage;

  protected ModelInvocationEntity() {}

  public ModelInvocationEntity(
      String id, String runId, String time, String agentId, String modelName) {
    super(id, runId, time);
    this.agentId = agentId;
    this.modelName = modelName;
  }
}
