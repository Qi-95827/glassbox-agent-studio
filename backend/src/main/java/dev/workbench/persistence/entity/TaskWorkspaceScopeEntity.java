package dev.workbench.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

/** 任务创建时冻结的目录授权、排除规则和展开后的有效文件集合。 */
@Entity
@Table(name = "task_workspace_scopes")
public class TaskWorkspaceScopeEntity {
  @Id
  @Column(name = "task_id", length = 36)
  private String taskId;

  @Column(name = "workspace_id", nullable = false, length = 36)
  private String workspaceId;

  @Lob
  @Column(name = "directory_scopes_json", nullable = false)
  private String directoryScopesJson;

  @Lob
  @Column(name = "excluded_paths_json", nullable = false)
  private String excludedPathsJson;

  @Lob
  @Column(name = "explicit_files_json", nullable = false)
  private String explicitFilesJson;

  @Lob
  @Column(name = "effective_files_json", nullable = false)
  private String effectiveFilesJson;

  @Column(name = "scope_sha256", nullable = false, length = 64)
  private String scopeSha256;

  @Column(name = "created_at", nullable = false, length = 40)
  private String createdAt;

  protected TaskWorkspaceScopeEntity() {}

  public TaskWorkspaceScopeEntity(
      String taskId,
      String workspaceId,
      String directoryScopesJson,
      String excludedPathsJson,
      String explicitFilesJson,
      String effectiveFilesJson,
      String scopeSha256,
      String createdAt) {
    this.taskId = taskId;
    this.workspaceId = workspaceId;
    this.directoryScopesJson = directoryScopesJson;
    this.excludedPathsJson = excludedPathsJson;
    this.explicitFilesJson = explicitFilesJson;
    this.effectiveFilesJson = effectiveFilesJson;
    this.scopeSha256 = scopeSha256;
    this.createdAt = createdAt;
  }

  public String getTaskId() {
    return taskId;
  }

  public String getWorkspaceId() {
    return workspaceId;
  }

  public String getDirectoryScopesJson() {
    return directoryScopesJson;
  }

  public String getExcludedPathsJson() {
    return excludedPathsJson;
  }

  public String getExplicitFilesJson() {
    return explicitFilesJson;
  }

  public String getEffectiveFilesJson() {
    return effectiveFilesJson;
  }

  public String getScopeSha256() {
    return scopeSha256;
  }

  public String getCreatedAt() {
    return createdAt;
  }
}
