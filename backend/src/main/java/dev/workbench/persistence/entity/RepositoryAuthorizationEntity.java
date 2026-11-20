package dev.workbench.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

/** 管理员显式授予某个账号导入的规范化仓库根目录。 */
@Entity
@Table(name = "repository_authorizations")
public class RepositoryAuthorizationEntity implements Persistable<String> {
  @Transient private boolean isNew = true;

  @Id
  @Column(name = "id", length = 36)
  private String id;

  @Column(name = "owner_scope_key", nullable = false, length = 80)
  private String ownerScopeKey;

  @Column(name = "repository_root", nullable = false, length = 2000)
  private String repositoryRoot;

  @Column(name = "repository_key", nullable = false, length = 64)
  private String repositoryKey;

  @Column(name = "granted_by", nullable = false, length = 36)
  private String grantedBy;

  @Column(name = "created_at", nullable = false, length = 40)
  private String createdAt;

  protected RepositoryAuthorizationEntity() {}

  public RepositoryAuthorizationEntity(
      String id,
      String ownerScopeKey,
      String repositoryRoot,
      String repositoryKey,
      String grantedBy,
      String createdAt) {
    this.id = id;
    this.ownerScopeKey = ownerScopeKey;
    this.repositoryRoot = repositoryRoot;
    this.repositoryKey = repositoryKey;
    this.grantedBy = grantedBy;
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

  public String getOwnerScopeKey() {
    return ownerScopeKey;
  }

  public String getRepositoryRoot() {
    return repositoryRoot;
  }

  public String getRepositoryKey() {
    return repositoryKey;
  }

  public String getGrantedBy() {
    return grantedBy;
  }

  public String getCreatedAt() {
    return createdAt;
  }
}
