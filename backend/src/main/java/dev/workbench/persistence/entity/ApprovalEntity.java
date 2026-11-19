package dev.workbench.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

/** 针对具体工作区差异的人工审查记录。 */
@Entity
@Table(name = "approvals")
public class ApprovalEntity implements Persistable<String> {
  @Transient private boolean isNew = true;

  @Id
  @Column(name = "id", length = 36)
  private String id;

  @Column(name = "workspace_id", nullable = false, length = 36)
  private String workspaceId;

  @Column(name = "fingerprint", nullable = false, length = 64)
  private String fingerprint;

  @Column(name = "status", nullable = false, length = 30)
  private String status;

  @Column(name = "created_at", nullable = false, length = 40)
  private String createdAt;

  @Column(name = "updated_at", nullable = false, length = 40)
  private String updatedAt;

  protected ApprovalEntity() {}

  public ApprovalEntity(
      String id,
      String workspaceId,
      String fingerprint,
      String status,
      String createdAt,
      String updatedAt) {
    this.id = id;
    this.workspaceId = workspaceId;
    this.fingerprint = fingerprint;
    this.status = status;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
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

  public String getWorkspaceId() {
    return workspaceId;
  }

  public String getFingerprint() {
    return fingerprint;
  }

  public String getStatus() {
    return status;
  }

  public String getCreatedAt() {
    return createdAt;
  }

  public String getUpdatedAt() {
    return updatedAt;
  }
}
