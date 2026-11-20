package dev.workbench.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;

/** 私有工具不可变版本；扩展名过滤在任务授权文件上再收窄。 */
@Entity
@Table(name = "private_tool_versions")
@IdClass(PrivateToolVersionEntity.Key.class)
public class PrivateToolVersionEntity {
  @Id
  @Column(name = "tool_id", length = 36)
  private String toolId;

  @Id
  @Column(name = "version")
  private int version;

  @Column(name = "name", nullable = false, length = 120)
  private String name;

  @Column(name = "description", nullable = false, length = 300)
  private String description;

  @Column(name = "executor_kind", nullable = false, length = 20)
  private String executorKind;

  @Column(name = "file_extensions_json", nullable = false, length = 2000)
  private String fileExtensionsJson;

  @Column(name = "spec_sha256", nullable = false, length = 64)
  private String specSha256;

  @Column(name = "created_at", nullable = false, length = 40)
  private String createdAt;

  protected PrivateToolVersionEntity() {}

  public PrivateToolVersionEntity(
      String toolId,
      int version,
      String name,
      String description,
      String executorKind,
      String fileExtensionsJson,
      String specSha256,
      String createdAt) {
    this.toolId = toolId;
    this.version = version;
    this.name = name;
    this.description = description;
    this.executorKind = executorKind;
    this.fileExtensionsJson = fileExtensionsJson;
    this.specSha256 = specSha256;
    this.createdAt = createdAt;
  }

  public String getToolId() {
    return toolId;
  }

  public int getVersion() {
    return version;
  }

  public String getName() {
    return name;
  }

  public String getDescription() {
    return description;
  }

  public String getExecutorKind() {
    return executorKind;
  }

  public String getFileExtensionsJson() {
    return fileExtensionsJson;
  }

  public String getSpecSha256() {
    return specSha256;
  }

  public String getCreatedAt() {
    return createdAt;
  }

  public static class Key implements Serializable {
    private String toolId;
    private int version;

    public Key() {}

    public Key(String toolId, int version) {
      this.toolId = toolId;
      this.version = version;
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof Key key
          && Objects.equals(toolId, key.toolId)
          && version == key.version;
    }

    @Override
    public int hashCode() {
      return Objects.hash(toolId, version);
    }
  }
}
