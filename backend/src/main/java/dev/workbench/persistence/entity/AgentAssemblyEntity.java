package dev.workbench.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "agent_assemblies")
public class AgentAssemblyEntity {
  @Id
  @Column(name = "agent_id", length = 36, nullable = false)
  private String agentId;

  @Column(name = "recorded_at", length = 40, nullable = false)
  private String recordedAt;

  @JdbcTypeCode(SqlTypes.LONGVARCHAR)
  @Column(name = "payload", nullable = false)
  private String payload;

  protected AgentAssemblyEntity() {}

  public AgentAssemblyEntity(String agentId, String recordedAt, String payload) {
    this.agentId = agentId;
    this.recordedAt = recordedAt;
    this.payload = payload;
  }
}
