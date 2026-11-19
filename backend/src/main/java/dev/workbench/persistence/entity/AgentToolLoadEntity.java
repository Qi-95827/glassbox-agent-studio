package dev.workbench.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "agent_tool_loads")
public class AgentToolLoadEntity {
  @Id
  @Column(name = "agent_id", length = 36, nullable = false)
  private String agentId;

  @Column(name = "loaded_at", length = 40, nullable = false)
  private String loadedAt;

  protected AgentToolLoadEntity() {}

  public AgentToolLoadEntity(String agentId, String loadedAt) {
    this.agentId = agentId;
    this.loadedAt = loadedAt;
  }
}
