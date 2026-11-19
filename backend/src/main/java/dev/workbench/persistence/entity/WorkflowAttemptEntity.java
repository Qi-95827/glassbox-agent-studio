package dev.workbench.persistence.entity;

import dev.workbench.domain.StopCause;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "workflow_attempts")
public class WorkflowAttemptEntity {
  @Id
  @Column(name = "id", length = 36, nullable = false)
  private String id;

  @Column(name = "node_id", length = 36, nullable = false)
  private String nodeId;

  @Column(name = "attempt_no", nullable = false)
  private Integer attemptNo;

  @Column(name = "agent_id", length = 36, nullable = false)
  private String agentId;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.VARCHAR)
  @Column(name = "stop_cause", length = 40)
  private StopCause stopCause;

  protected WorkflowAttemptEntity() {}

  public WorkflowAttemptEntity(String id, String nodeId, Integer attemptNo, String agentId) {
    this.id = id;
    this.nodeId = nodeId;
    this.attemptNo = attemptNo;
    this.agentId = agentId;
  }
}
