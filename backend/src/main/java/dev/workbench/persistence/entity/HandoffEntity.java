package dev.workbench.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "handoffs")
public class HandoffEntity {
  @Id
  @Column(name = "id", length = 36, nullable = false)
  private String id;

  @Column(name = "run_id", length = 36, nullable = false)
  private String runId;

  @Column(name = "agent_id", length = 36, nullable = false)
  private String agentId;

  @Column(name = "direction", length = 30, nullable = false)
  private String direction;

  @JdbcTypeCode(SqlTypes.LONGVARCHAR)
  @Column(name = "payload", nullable = false)
  private String payload;

  @Column(name = "created_at", length = 40, nullable = false)
  private String createdAt;

  protected HandoffEntity() {}

  public HandoffEntity(
      String id, String runId, String agentId, String direction, String payload, String createdAt) {
    this.id = id;
    this.runId = runId;
    this.agentId = agentId;
    this.direction = direction;
    this.payload = payload;
    this.createdAt = createdAt;
  }
}
