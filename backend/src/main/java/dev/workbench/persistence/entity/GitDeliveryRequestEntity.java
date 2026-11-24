package dev.workbench.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

/** 推送或合并的交付请求。批准时绑定的远端地址摘要、源提交和待合入提交保留在行内， 执行前由服务重新核对。 */
@Entity
@Table(name = "git_delivery_requests")
public class GitDeliveryRequestEntity implements Persistable<String> {
  @Transient private boolean isNew = true;

  @Id
  @Column(name = "id", length = 36)
  private String id;

  @Column(name = "workspace_id", nullable = false, length = 36)
  private String workspaceId;

  @Column(name = "kind", nullable = false, length = 30)
  private String kind;

  @Column(name = "remote", nullable = false, length = 80)
  private String remote;

  @Column(name = "target", nullable = false, length = 200)
  private String target;

  @Column(name = "target_commit", length = 64)
  private String targetCommit;

  @Column(name = "head_commit", nullable = false, length = 64)
  private String headCommit;

  @Column(name = "source_branch", nullable = false, length = 200)
  private String sourceBranch;

  @Column(name = "remote_url_sha256", length = 64)
  private String remoteUrlSha256;

  @Column(name = "status", nullable = false, length = 30)
  private String status;

  @Column(name = "note", length = 500)
  private String note;

  @Column(name = "result_detail", length = 1000)
  private String resultDetail;

  @Column(name = "created_at", nullable = false, length = 40)
  private String createdAt;

  @Column(name = "updated_at", nullable = false, length = 40)
  private String updatedAt;

  protected GitDeliveryRequestEntity() {}

  public GitDeliveryRequestEntity(
      String id,
      String workspaceId,
      String kind,
      String remote,
      String target,
      String targetCommit,
      String headCommit,
      String sourceBranch,
      String remoteUrlSha256,
      String status,
      String note,
      String resultDetail,
      String createdAt,
      String updatedAt) {
    this.id = id;
    this.workspaceId = workspaceId;
    this.kind = kind;
    this.remote = remote;
    this.target = target;
    this.targetCommit = targetCommit;
    this.headCommit = headCommit;
    this.sourceBranch = sourceBranch;
    this.remoteUrlSha256 = remoteUrlSha256;
    this.status = status;
    this.note = note;
    this.resultDetail = resultDetail;
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

  public String getRemote() {
    return remote;
  }

  public String getTarget() {
    return target;
  }

  public String getTargetCommit() {
    return targetCommit;
  }

  public String getHeadCommit() {
    return headCommit;
  }

  public String getSourceBranch() {
    return sourceBranch;
  }

  public String getRemoteUrlSha256() {
    return remoteUrlSha256;
  }

  public String getStatus() {
    return status;
  }

  public String getNote() {
    return note;
  }

  public String getResultDetail() {
    return resultDetail;
  }

  public String getCreatedAt() {
    return createdAt;
  }

  public String getUpdatedAt() {
    return updatedAt;
  }
}
