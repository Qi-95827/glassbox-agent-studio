package dev.workbench.persistence.entity;

import dev.workbench.domain.MemoryCategory;
import dev.workbench.domain.MemorySource;
import dev.workbench.domain.MemoryStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

/** 用户确认的项目记忆；版本和软删除由工作区内的条件更新维护。 */
@Entity
@Table(name = "memory_records")
public class MemoryRecordEntity implements Persistable<String> {
  @Transient private boolean isNew = true;

  @Id
  @Column(name = "id", length = 36)
  private String id;

  @Column(name = "workspace_id", nullable = false, length = 36)
  private String workspaceId;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.VARCHAR)
  @Column(name = "category", nullable = false, length = 30)
  private MemoryCategory category;

  @Column(name = "content", nullable = false, length = 300)
  private String content;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.VARCHAR)
  @Column(name = "source", nullable = false, length = 30)
  private MemorySource source;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.VARCHAR)
  @Column(name = "status", nullable = false, length = 20)
  private MemoryStatus status;

  @Column(name = "version", nullable = false)
  private int version;

  @Column(name = "created_at", nullable = false, length = 40)
  private String createdAt;

  @Column(name = "updated_at", nullable = false, length = 40)
  private String updatedAt;

  protected MemoryRecordEntity() {}

  public MemoryRecordEntity(
      String id,
      String workspaceId,
      MemoryCategory category,
      String content,
      MemorySource source,
      MemoryStatus status,
      int version,
      String createdAt,
      String updatedAt) {
    this.id = id;
    this.workspaceId = workspaceId;
    this.category = category;
    this.content = content;
    this.source = source;
    this.status = status;
    this.version = version;
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

  public MemoryCategory getCategory() {
    return category;
  }

  public String getContent() {
    return content;
  }

  public MemorySource getSource() {
    return source;
  }

  public MemoryStatus getStatus() {
    return status;
  }

  public int getVersion() {
    return version;
  }

  public String getCreatedAt() {
    return createdAt;
  }

  public String getUpdatedAt() {
    return updatedAt;
  }
}
