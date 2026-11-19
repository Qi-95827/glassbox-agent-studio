package dev.workbench.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

/** 用户触发的本地 Git 动作，记录关联审批和最终提交。 */
@Entity
@Table(name = "git_actions")
public class GitActionEntity implements Persistable<String> {
  @Transient private boolean isNew = true;

  @Id
  @Column(name = "id", length = 36)
  private String id;

  @Column(name = "workspace_id", nullable = false, length = 36)
  private String workspaceId;

  @Column(name = "kind", nullable = false, length = 30)
  private String kind;

  @Column(name = "status", nullable = false, length = 30)
  private String status;

  @Column(name = "approval_id", nullable = false, length = 36)
  private String approvalId;

  @Column(name = "commit_id", length = 64)
  private String commitId;

  @Column(name = "target", length = 300)
  private String target;

  @Column(name = "detail", length = 1000)
  private String detail;

  @Column(name = "created_at", nullable = false, length = 40)
  private String createdAt;

  @Column(name = "updated_at", nullable = false, length = 40)
  private String updatedAt;

  protected GitActionEntity() {}

  public GitActionEntity(
      String id,
      String workspaceId,
      String kind,
      String status,
      String approvalId,
      String commitId,
      String target,
      String detail,
      String createdAt,
      String updatedAt) {
    this.id = id;
    this.workspaceId = workspaceId;
    this.kind = kind;
    this.status = status;
    this.approvalId = approvalId;
    this.commitId = commitId;
    this.target = target;
    this.detail = detail;
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

  public String getKind() {
    return kind;
  }

  public String getStatus() {
    return status;
  }

  public String getApprovalId() {
    return approvalId;
  }

  public String getCommitId() {
    return commitId;
  }

  public String getTarget() {
    return target;
  }

  public String getDetail() {
    return detail;
  }

  public String getCreatedAt() {
    return createdAt;
  }

  public String getUpdatedAt() {
    return updatedAt;
  }
}
