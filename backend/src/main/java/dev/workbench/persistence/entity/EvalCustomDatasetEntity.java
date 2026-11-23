package dev.workbench.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 用户自定义评测集；按 owner_scope_key 严格隔离。 */
@Entity
@Table(name = "eval_custom_datasets")
public class EvalCustomDatasetEntity {
  @Id
  @Column(name = "id", length = 36)
  private String id;

  @Column(name = "owner_scope_key", nullable = false, length = 80)
  private String ownerScopeKey;

  @Column(name = "code", nullable = false, length = 40)
  private String code;

  @Column(name = "name", nullable = false, length = 120)
  private String name;

  @Column(name = "category", nullable = false, length = 40)
  private String category;

  @Column(name = "description", length = 500)
  private String description;

  @Column(name = "created_at", nullable = false, length = 40)
  private String createdAt;

  @Column(name = "updated_at", nullable = false, length = 40)
  private String updatedAt;

  protected EvalCustomDatasetEntity() {}

  public EvalCustomDatasetEntity(
      String id,
      String ownerScopeKey,
      String code,
      String name,
      String category,
      String description,
      String createdAt,
      String updatedAt) {
    this.id = id;
    this.ownerScopeKey = ownerScopeKey;
    this.code = code;
    this.name = name;
    this.category = category;
    this.description = description;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
  }

  public String getId() {
    return id;
  }

  public String getOwnerScopeKey() {
    return ownerScopeKey;
  }

  public String getCode() {
    return code;
  }

  public String getName() {
    return name;
  }

  public String getCategory() {
    return category;
  }

  public String getDescription() {
    return description;
  }

  public String getCreatedAt() {
    return createdAt;
  }

  public String getUpdatedAt() {
    return updatedAt;
  }

  public void update(String name, String category, String description, String at) {
    this.name = name;
    this.category = category;
    this.description = description;
    this.updatedAt = at;
  }
}
