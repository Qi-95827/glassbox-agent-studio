package dev.workbench.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

/** 用户私有只读工具配置；执行器仍是受信读取或搜索实现。 */
@Entity
@Table(name = "private_tools")
public class PrivateToolEntity implements Persistable<String> {
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

  @Column(name = "executor_kind", nullable = false, length = 20)
  private String executorKind;

  @Column(name = "file_extensions_json", nullable = false, length = 2000)
  private String fileExtensionsJson;

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

  protected PrivateToolEntity() {}

  public PrivateToolEntity(String id, String ownerScopeKey, String name, String createdAt) {
    this.id = id;
    this.ownerScopeKey = ownerScopeKey;
    this.name = name;
    this.status = "DRAFT";
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

  public String getExecutorKind() {
    return executorKind;
  }

  public void setExecutorKind(String executorKind) {
    this.executorKind = executorKind;
  }

  public String getFileExtensionsJson() {
    return fileExtensionsJson;
  }

  public void setFileExtensionsJson(String fileExtensionsJson) {
    this.fileExtensionsJson = fileExtensionsJson;
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
