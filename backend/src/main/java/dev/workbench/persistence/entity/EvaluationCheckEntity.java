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
import org.springframework.data.domain.Persistable;

/** 单个样本的固定断言明细；复合主键与数据库一致。 */
@Entity
@Table(name = "evaluation_checks")
public class EvaluationCheckEntity implements Persistable<EvaluationCheckEntity.Id> {
  @Transient private boolean isNew = true;

  @EmbeddedId private Id id;

  @Column(name = "sequence_no", nullable = false)
  private int sequenceNo;

  @Column(name = "passed", nullable = false)
  private boolean passed;

  @Column(name = "detail", length = 1000, nullable = false)
  private String detail;

  protected EvaluationCheckEntity() {}

  public EvaluationCheckEntity(
      String caseId, int sequenceNo, String checkCode, boolean passed, String detail) {
    this.id = new Id(caseId, checkCode);
    this.sequenceNo = sequenceNo;
    this.passed = passed;
    this.detail = detail;
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

  public int getSequenceNo() {
    return sequenceNo;
  }

  public boolean isPassed() {
    return passed;
  }

  public String getDetail() {
    return detail;
  }

  @Embeddable
  public static class Id implements Serializable {
    @Column(name = "case_id", length = 36, nullable = false)
    private String caseId;

    @Column(name = "check_code", length = 50, nullable = false)
    private String checkCode;

    protected Id() {}

    public Id(String caseId, String checkCode) {
      this.caseId = caseId;
      this.checkCode = checkCode;
    }

    public String getCaseId() {
      return caseId;
    }

    public String getCheckCode() {
      return checkCode;
    }

    @Override
    public boolean equals(Object other) {
      if (this == other) return true;
      if (!(other instanceof Id id)) return false;
      return Objects.equals(caseId, id.caseId) && Objects.equals(checkCode, id.checkCode);
    }

    @Override
    public int hashCode() {
      return Objects.hash(caseId, checkCode);
    }
  }
}
