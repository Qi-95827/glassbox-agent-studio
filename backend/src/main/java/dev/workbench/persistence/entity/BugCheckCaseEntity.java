package dev.workbench.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.io.Serializable;
import java.util.Objects;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

/** 检测的逐用例结果；复合主键与数据库一致，顺序号保留解析顺序。 */
@Entity
@Table(name = "bug_check_cases")
public class BugCheckCaseEntity implements Persistable<BugCheckCaseEntity.Id> {
  @Transient private boolean isNew = true;

  @EmbeddedId private Id id;

  @Column(name = "test_id", length = 300, nullable = false)
  private String testId;

  @Column(name = "result", length = 30, nullable = false)
  private String result;

  @JdbcTypeCode(SqlTypes.LONGVARCHAR)
  @Column(name = "message")
  private String message;

  protected BugCheckCaseEntity() {}

  public BugCheckCaseEntity(
      String checkId, int sequenceNo, String testId, String result, String message) {
    this.id = new Id(checkId, sequenceNo);
    this.testId = testId;
    this.result = result;
    this.message = message;
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

  public String getTestId() {
    return testId;
  }

  public String getResult() {
    return result;
  }

  public String getMessage() {
    return message;
  }

  @Embeddable
  public static class Id implements Serializable {
    @Column(name = "check_id", length = 36, nullable = false)
    private String checkId;

    @Column(name = "sequence_no", nullable = false)
    private int sequenceNo;

    protected Id() {}

    public Id(String checkId, int sequenceNo) {
      this.checkId = checkId;
      this.sequenceNo = sequenceNo;
    }

    public String getCheckId() {
      return checkId;
    }

    public int getSequenceNo() {
      return sequenceNo;
    }

    @Override
    public boolean equals(Object other) {
      if (this == other) return true;
      if (!(other instanceof Id id)) return false;
      return sequenceNo == id.sequenceNo && Objects.equals(checkId, id.checkId);
    }

    @Override
    public int hashCode() {
      return Objects.hash(checkId, sequenceNo);
    }
  }
}
