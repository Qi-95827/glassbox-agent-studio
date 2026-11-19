package dev.workbench.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;

@Entity
@Table(name = "agent_tool_grants")
public class AgentToolGrantEntity {
  @EmbeddedId private Key id;

  @Column(name = "tool_version")
  private Integer toolVersion;

  protected AgentToolGrantEntity() {}

  public AgentToolGrantEntity(String agentId, String toolKind, Integer toolVersion) {
    this.id = new Key(agentId, toolKind);
    this.toolVersion = toolVersion;
  }

  @Embeddable
  public static class Key implements Serializable {
    @Column(name = "agent_id", length = 36)
    private String agentId;

    @Column(name = "tool_kind", length = 40)
    private String toolKind;

    protected Key() {}

    Key(String agentId, String toolKind) {
      this.agentId = agentId;
      this.toolKind = toolKind;
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof Key key
          && Objects.equals(agentId, key.agentId)
          && Objects.equals(toolKind, key.toolKind);
    }

    @Override
    public int hashCode() {
      return Objects.hash(agentId, toolKind);
    }
  }
}
