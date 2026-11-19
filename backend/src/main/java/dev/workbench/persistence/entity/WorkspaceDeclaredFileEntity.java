package dev.workbench.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;

/**
 * 用户显式声明的补充文件。复合主键沿用 (workspace_id, path)；保存使用合并语义，
 * 同一路径再次声明时更新摘要、来源和时间。
 */
@Entity
@Table(name = "workspace_declared_files")
public class WorkspaceDeclaredFileEntity {
  @EmbeddedId private Key id;

  @Column(name = "sha256", nullable = false, length = 64)
  private String sha256;

  @Column(name = "source", nullable = false, length = 30)
  private String source;

  @Column(name = "created_at", nullable = false, length = 40)
  private String createdAt;

  protected WorkspaceDeclaredFileEntity() {}

  public WorkspaceDeclaredFileEntity(
      String workspaceId, String path, String sha256, String source, String createdAt) {
    this.id = new Key(workspaceId, path);
    this.sha256 = sha256;
    this.source = source;
    this.createdAt = createdAt;
  }

  public Key getId() {
    return id;
  }

  public String getWorkspaceId() {
    return id == null ? null : id.getWorkspaceId();
  }

  public String getPath() {
    return id == null ? null : id.getPath();
  }

  public String getSha256() {
    return sha256;
  }

  public String getSource() {
    return source;
  }

  public String getCreatedAt() {
    return createdAt;
  }

  @Embeddable
  public static class Key implements Serializable {
    @Column(name = "workspace_id", length = 36, nullable = false)
    private String workspaceId;

    @Column(name = "path", length = 200, nullable = false)
    private String path;

    protected Key() {}

    public Key(String workspaceId, String path) {
      this.workspaceId = workspaceId;
      this.path = path;
    }

    public String getWorkspaceId() {
      return workspaceId;
    }

    public String getPath() {
      return path;
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof Key key
          && Objects.equals(workspaceId, key.workspaceId)
          && Objects.equals(path, key.path);
    }

    @Override
    public int hashCode() {
      return Objects.hash(workspaceId, path);
    }
  }
}
