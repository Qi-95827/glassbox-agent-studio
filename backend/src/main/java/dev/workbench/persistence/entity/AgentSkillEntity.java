package dev.workbench.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;

@Entity
@Table(name = "agent_skills")
public class AgentSkillEntity {
  @EmbeddedId private Key id;

  @Column(name = "skill_version", nullable = false)
  private Integer skillVersion;

  @Column(name = "content_sha256", length = 64, nullable = false)
  private String contentSha256;

  @Column(name = "created_at", length = 40, nullable = false)
  private String createdAt;

  protected AgentSkillEntity() {}

  public AgentSkillEntity(
      String agentId,
      String skillId,
      Integer skillVersion,
      String contentSha256,
      String createdAt) {
    this.id = new Key(agentId, skillId);
    this.skillVersion = skillVersion;
    this.contentSha256 = contentSha256;
    this.createdAt = createdAt;
  }

  @Embeddable
  public static class Key implements Serializable {
    @Column(name = "agent_id", length = 36)
    private String agentId;

    @Column(name = "skill_id", length = 40)
    private String skillId;

    protected Key() {}

    Key(String agentId, String skillId) {
      this.agentId = agentId;
      this.skillId = skillId;
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof Key key
          && Objects.equals(agentId, key.agentId)
          && Objects.equals(skillId, key.skillId);
    }

    @Override
    public int hashCode() {
      return Objects.hash(agentId, skillId);
    }
  }
}
