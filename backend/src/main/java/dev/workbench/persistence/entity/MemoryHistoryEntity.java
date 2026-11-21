package dev.workbench.persistence.entity;

import dev.workbench.domain.MemoryCategory;
import dev.workbench.domain.MemoryStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.io.Serializable;
import java.util.Objects;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

/** 项目记忆的不可变版本快照；主键沿用业务复合键 (memory_id, version)。 */
@Entity
@Table(name = "memory_history")
public class MemoryHistoryEntity implements Persistable<MemoryHistoryEntity.Id> {
  @Transient private boolean isNew = true;

  @EmbeddedId private Id id;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.VARCHAR)
  @Column(name = "category", nullable = false, length = 30)
  private MemoryCategory category;

  @Column(name = "content", nullable = false, length = 300)
  private String content;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.VARCHAR)
  @Column(name = "status", nullable = false, length = 20)
  private MemoryStatus status;

  @Column(name = "changed_at", nullable = false, length = 40)
  private String changedAt;

  protected MemoryHistoryEntity() {}

  public MemoryHistoryEntity(
      String memoryId,
      int version,
      MemoryCategory category,
      String content,
      MemoryStatus status,
      String changedAt) {
    this.id = new Id(memoryId, version);
    this.category = category;
    this.content = content;
    this.status = status;
    this.changedAt = changedAt;
  }

  @Override
  public Id getId() {
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

  public MemoryCategory getCategory() {
    return category;
  }

  public String getContent() {
    return content;
  }

  public MemoryStatus getStatus() {
    return status;
  }

  public String getChangedAt() {
    return changedAt;
  }

  @Embeddable
  public static class Id implements Serializable {
    @Column(name = "memory_id", length = 36)
    private String memoryId;

    @Column(name = "version")
    private int version;

    protected Id() {}

    public Id(String memoryId, int version) {
      this.memoryId = memoryId;
      this.version = version;
    }

    public String getMemoryId() {
      return memoryId;
    }

    public int getVersion() {
      return version;
    }

    @Override
    public boolean equals(Object other) {
      if (this == other) return true;
      if (!(other instanceof Id id)) return false;
      return version == id.version && Objects.equals(memoryId, id.memoryId);
    }

    @Override
    public int hashCode() {
      return Objects.hash(memoryId, version);
    }
  }
}
