package dev.workbench.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

/** 本地 Git 工作区。主键由业务预分配，创建路径只插入。 */
@Entity
@Table(name = "workspaces")
public class WorkspaceEntity implements Persistable<String> {
  @Transient private boolean isNew = true;

  @Id
  @Column(name = "id", length = 36)
  private String id;

  @Column(name = "repository_path", nullable = false, length = 2000)
  private String repositoryPath;

  @Column(name = "workspace_path", nullable = false, length = 2000)
  private String workspacePath;

  @Column(name = "base_branch", nullable = false, length = 500)
  private String baseBranch;

  @Column(name = "base_commit", nullable = false, length = 64)
  private String baseCommit;

  @Column(name = "branch_name", nullable = false, length = 500)
  private String branchName;

  @Column(name = "created_at", nullable = false, length = 40)
  private String createdAt;

  protected WorkspaceEntity() {}

  public WorkspaceEntity(
      String id,
      String repositoryPath,
      String workspacePath,
      String baseBranch,
      String baseCommit,
      String branchName,
      String createdAt) {
    this.id = id;
    this.repositoryPath = repositoryPath;
    this.workspacePath = workspacePath;
    this.baseBranch = baseBranch;
    this.baseCommit = baseCommit;
    this.branchName = branchName;
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

  public String getRepositoryPath() {
    return repositoryPath;
  }

  public String getWorkspacePath() {
    return workspacePath;
  }

  public String getBaseBranch() {
    return baseBranch;
  }

  public String getBaseCommit() {
    return baseCommit;
  }

  public String getBranchName() {
    return branchName;
  }

  public String getCreatedAt() {
    return createdAt;
  }
}
