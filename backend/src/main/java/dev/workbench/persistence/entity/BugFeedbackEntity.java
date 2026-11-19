package dev.workbench.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

/** 用户显式提交的模拟 Bug 反馈；不自动关闭。 */
@Entity
@Table(name = "bug_feedback")
public class BugFeedbackEntity implements Persistable<String> {
  @Transient private boolean isNew = true;

  @Id
  @Column(name = "id", length = 36, nullable = false)
  private String id;

  @Column(name = "bug_id", length = 80, nullable = false)
  private String bugId;

  @Column(name = "workspace_id", length = 36, nullable = false)
  private String workspaceId;

  @Column(name = "status", length = 40, nullable = false)
  private String status;

  @Column(name = "note", length = 1000)
  private String note;

  @Column(name = "created_at", length = 40, nullable = false)
  private String createdAt;

  protected BugFeedbackEntity() {}

  public BugFeedbackEntity(
      String id, String bugId, String workspaceId, String status, String note, String createdAt) {
    this.id = id;
    this.bugId = bugId;
    this.workspaceId = workspaceId;
    this.status = status;
    this.note = note;
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

  public String getBugId() {
    return bugId;
  }

  public String getWorkspaceId() {
    return workspaceId;
  }

  public String getStatus() {
    return status;
  }

  public String getNote() {
    return note;
  }

  public String getCreatedAt() {
    return createdAt;
  }
}
