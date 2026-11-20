package dev.workbench.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

/**
 * 资源归属映射。
 *
 * <p>任务与工作区沿用原表，不额外要求旧库结构；新资源创建后在这里登记归属。没有登记的历史记录 视为旧 LOCAL 数据，只由已领取旧数据的管理员读取。
 */
@Entity
@Table(name = "resource_ownership")
public class ResourceOwnershipEntity implements Persistable<String> {
  @Transient private boolean isNew = true;

  @Id
  @Column(name = "id", length = 120)
  private String id;

  @Column(name = "resource_type", nullable = false, length = 30)
  private String resourceType;

  @Column(name = "resource_id", nullable = false, length = 36)
  private String resourceId;

  @Column(name = "owner_scope_key", nullable = false, length = 80)
  private String ownerScopeKey;

  @Column(name = "created_at", nullable = false, length = 40)
  private String createdAt;

  protected ResourceOwnershipEntity() {}

  public ResourceOwnershipEntity(
      String resourceType, String resourceId, String ownerScopeKey, String createdAt) {
    this.id = resourceType + ":" + resourceId;
    this.resourceType = resourceType;
    this.resourceId = resourceId;
    this.ownerScopeKey = ownerScopeKey;
    this.createdAt = createdAt;
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

  public String getResourceType() {
    return resourceType;
  }

  public String getResourceId() {
    return resourceId;
  }

  public String getOwnerScopeKey() {
    return ownerScopeKey;
  }

  public String getCreatedAt() {
    return createdAt;
  }
}
