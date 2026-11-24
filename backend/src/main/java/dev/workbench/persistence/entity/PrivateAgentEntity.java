package dev.workbench.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

/** 用户私有 Agent 的可编辑草稿；发布时固定 Skill、工具和权限类别。 */
@Entity
@Table(name = "private_agents")
public class PrivateAgentEntity implements Persistable<String> {
  @Transient private boolean isNew = true;

  @Id
  @Column(name = "id", length = 36)
  private String id;

  @Column(name = "owner_scope_key", nullable = false, length = 80)
  private String ownerScopeKey;

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

  /** INHERIT_TASK 或 FIXED；固定模式使用 model_mode。 */
  @Column(name = "model_strategy", nullable = false, length = 20)
  private String modelStrategy;

  @Column(name = "skill_refs_json", nullable = false, length = 4000)
  private String skillRefsJson;

  @Column(name = "tool_refs_json", nullable = false, length = 4000)
  private String toolRefsJson;

  @Column(name = "draft_sha256", nullable = false, length = 64)
  private String draftSha256;

  @Column(name = "status", nullable = false, length = 20)
  private String status;

  @Column(name = "current_version", nullable = false)
  private int currentVersion;

  @Column(name = "created_at", nullable = false, length = 40)
  private String createdAt;

  @Column(name = "updated_at", nullable = false, length = 40)
  private String updatedAt;

  @Column(name = "archived_at", length = 40)
  private String archivedAt;

  protected PrivateAgentEntity() {}

  public PrivateAgentEntity(String id, String ownerScopeKey, String name, String createdAt) {
    this.id = id;
    this.ownerScopeKey = ownerScopeKey;
    this.name = name;
    this.status = "DRAFT";
    this.permissionRole = "ANALYST";
    this.modelMode = "mock";
    this.modelStrategy = "INHERIT_TASK";
    this.skillRefsJson = "[]";
    this.toolRefsJson = "[]";
    this.createdAt = createdAt;
    this.updatedAt = createdAt;
  }

  @Override
  public String getId() {
    return id;
  }

  @Override
  public boolean isNew() {
    return isNew;
  }

  @PostLoad
  @PostPersist
  void markNotNew() {
    isNew = false;
  }

  public String getOwnerScopeKey() {
    return ownerScopeKey;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getDescription() {
    return description;
  }

  public void setDescription(String description) {
    this.description = description;
  }

  public String getResponsibility() {
    return responsibility;
  }

  public void setResponsibility(String responsibility) {
    this.responsibility = responsibility;
  }

  public String getOutputRequirements() {
    return outputRequirements;
  }

  public void setOutputRequirements(String outputRequirements) {
    this.outputRequirements = outputRequirements;
  }

  public String getPermissionRole() {
    return permissionRole;
  }

  public void setPermissionRole(String permissionRole) {
    this.permissionRole = permissionRole;
  }

  public boolean isRecommendable() {
    return recommendable;
  }

  public void setRecommendable(boolean recommendable) {
    this.recommendable = recommendable;
  }

  public String getModelMode() {
    return modelMode;
  }

  public void setModelMode(String modelMode) {
    this.modelMode = modelMode;
  }

  public String getModelStrategy() {
    return modelStrategy;
  }

  public void setModelStrategy(String modelStrategy) {
    this.modelStrategy = modelStrategy;
  }

  public String getSkillRefsJson() {
    return skillRefsJson;
  }

  public void setSkillRefsJson(String skillRefsJson) {
    this.skillRefsJson = skillRefsJson;
  }

  public String getToolRefsJson() {
    return toolRefsJson;
  }

  public void setToolRefsJson(String toolRefsJson) {
    this.toolRefsJson = toolRefsJson;
  }

  public String getDraftSha256() {
    return draftSha256;
  }

  public void setDraftSha256(String draftSha256) {
    this.draftSha256 = draftSha256;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    this.status = status;
  }

  public int getCurrentVersion() {
    return currentVersion;
  }

  public void setCurrentVersion(int currentVersion) {
    this.currentVersion = currentVersion;
  }

  public String getCreatedAt() {
    return createdAt;
  }

  public String getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(String updatedAt) {
    this.updatedAt = updatedAt;
  }

  public String getArchivedAt() {
    return archivedAt;
  }

  public void setArchivedAt(String archivedAt) {
    this.archivedAt = archivedAt;
  }
}
