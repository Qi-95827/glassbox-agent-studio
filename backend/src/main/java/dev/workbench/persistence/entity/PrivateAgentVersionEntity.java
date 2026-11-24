package dev.workbench.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;

/** 私有 Agent 的不可变发布版本；旧运行始终按这里的组件快照解释。 */
@Entity
@Table(name = "private_agent_versions")
@IdClass(PrivateAgentVersionEntity.Key.class)
public class PrivateAgentVersionEntity {
  @Id
  @Column(name = "agent_id", length = 36)
  private String agentId;

  @Id
  @Column(name = "version")
  private int version;

  @Column(name = "name", nullable = false, length = 120)
  private String name;

  @Column(name = "description", nullable = false, length = 300)
  private String description;

  @Column(name = "responsibility", nullable = false)
  private String responsibility;

  @Column(name = "output_requirements", length = 2000)
  private String outputRequirements;

  @Column(name = "permission_role", nullable = false, length = 20)
  private String permissionRole;

  @Column(name = "recommendable", nullable = false)
  private boolean recommendable;

  @Column(name = "model_mode", nullable = false, length = 20)
  private String modelMode;

  @Column(name = "model_strategy", nullable = false, length = 20)
  private String modelStrategy;

  @Column(name = "components_json", nullable = false)
  private String componentsJson;

  @Column(name = "spec_sha256", nullable = false, length = 64)
  private String specSha256;

  @Column(name = "created_at", nullable = false, length = 40)
  private String createdAt;

  protected PrivateAgentVersionEntity() {}

  public PrivateAgentVersionEntity(
      String agentId,
      int version,
      String name,
      String description,
      String responsibility,
      String outputRequirements,
      String permissionRole,
      boolean recommendable,
      String modelMode,
      String modelStrategy,
      String componentsJson,
      String specSha256,
      String createdAt) {
    this.agentId = agentId;
    this.version = version;
    this.name = name;
    this.description = description;
    this.responsibility = responsibility;
    this.outputRequirements = outputRequirements;
    this.permissionRole = permissionRole;
    this.recommendable = recommendable;
    this.modelMode = modelMode;
    this.modelStrategy = modelStrategy;
    this.componentsJson = componentsJson;
    this.specSha256 = specSha256;
    this.createdAt = createdAt;
  }

  public String getAgentId() {
    return agentId;
  }

  public int getVersion() {
    return version;
  }

  public String getName() {
    return name;
  }

  public String getDescription() {
    return description;
  }

  public String getResponsibility() {
    return responsibility;
  }

  public String getOutputRequirements() {
    return outputRequirements;
  }

  public String getPermissionRole() {
    return permissionRole;
  }

  public boolean isRecommendable() {
    return recommendable;
  }

  public String getModelMode() {
    return modelMode;
  }

  public String getModelStrategy() {
    return modelStrategy;
  }

  public String getComponentsJson() {
    return componentsJson;
  }

  public String getSpecSha256() {
    return specSha256;
  }

  public String getCreatedAt() {
    return createdAt;
  }

  public static class Key implements Serializable {
    private String agentId;
    private int version;

    public Key() {}

    public Key(String agentId, int version) {
      this.agentId = agentId;
      this.version = version;
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof Key key
          && Objects.equals(agentId, key.agentId)
          && version == key.version;
    }

    @Override
    public int hashCode() {
      return Objects.hash(agentId, version);
    }
  }
}
