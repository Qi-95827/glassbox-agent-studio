package dev.workbench.persistence.entity;

import dev.workbench.domain.MemoryCategory;
import dev.workbench.framework.memory.MemoryType;
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

/** 运行装配引用：按 sequence_no 保留当时选中的记忆版本和类别。 */
@Entity
@Table(name = "run_memory_references")
public class RunMemoryReferenceEntity implements Persistable<RunMemoryReferenceEntity.Id> {
  @Transient private boolean isNew = true;

  @EmbeddedId private Id id;

  @Column(name = "memory_id", nullable = false, length = 36)
  private String memoryId;

  @Column(name = "memory_version", nullable = false)
  private int memoryVersion;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.VARCHAR)
  @Column(name = "category", nullable = false, length = 30)
  private MemoryCategory category;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.VARCHAR)
  @Column(name = "memory_type", nullable = false, length = 20)
  private MemoryType memoryType;

  @Column(name = "score", nullable = false)
  private double score;

  @Column(name = "matched_reason", length = 500)
  private String matchedReason;

  protected RunMemoryReferenceEntity() {}

  public RunMemoryReferenceEntity(
      String runId, int sequenceNo, String memoryId, int memoryVersion, MemoryCategory category) {
    this(
        runId,
        sequenceNo,
        memoryId,
        memoryVersion,
        category,
        MemoryType.SEMANTIC,
        0,
        null);
  }

  public RunMemoryReferenceEntity(
      String runId,
      int sequenceNo,
      String memoryId,
      int memoryVersion,
      MemoryCategory category,
      MemoryType memoryType,
      double score,
      String matchedReason) {
    this.id = new Id(runId, sequenceNo);
    this.memoryId = memoryId;
    this.memoryVersion = memoryVersion;
    this.category = category;
    this.memoryType = memoryType == null ? MemoryType.SEMANTIC : memoryType;
    this.score = score;
    this.matchedReason = matchedReason;
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

  public String getMemoryId() {
    return memoryId;
  }

  public int getMemoryVersion() {
    return memoryVersion;
  }

  public MemoryCategory getCategory() {
    return category;
  }

  public MemoryType getMemoryType() {
    return memoryType;
  }

  public double getScore() {
    return score;
  }

  public String getMatchedReason() {
    return matchedReason;
  }

  @Embeddable
  public static class Id implements Serializable {
    @Column(name = "run_id", length = 36)
    private String runId;

    @Column(name = "sequence_no")
    private int sequenceNo;

    protected Id() {}

    public Id(String runId, int sequenceNo) {
      this.runId = runId;
      this.sequenceNo = sequenceNo;
    }

    public String getRunId() {
      return runId;
    }

    public int getSequenceNo() {
      return sequenceNo;
    }

    @Override
    public boolean equals(Object other) {
      if (this == other) return true;
      if (!(other instanceof Id id)) return false;
      return sequenceNo == id.sequenceNo && Objects.equals(runId, id.runId);
    }

    @Override
    public int hashCode() {
      return Objects.hash(runId, sequenceNo);
    }
  }
}
