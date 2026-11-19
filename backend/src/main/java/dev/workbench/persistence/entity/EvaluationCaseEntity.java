package dev.workbench.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

/** 评测样本状态；关联运行保留，便于失败案例跳转。 */
@Entity
@Table(name = "evaluation_cases")
public class EvaluationCaseEntity implements Persistable<String> {
  @Transient private boolean isNew = true;

  @Id
  @Column(name = "id", length = 36, nullable = false)
  private String id;

  @Column(name = "batch_id", length = 36, nullable = false)
  private String batchId;

  @Column(name = "case_code", length = 40, nullable = false)
  private String caseCode;

  @Column(name = "sequence_no", nullable = false)
  private int sequenceNo;

  @Column(name = "status", length = 30, nullable = false)
  private String status;

  @Column(name = "run_id", length = 36)
  private String runId;

  @Column(name = "explanation", length = 1000)
  private String explanation;

  @Column(name = "updated_at", length = 40, nullable = false)
  private String updatedAt;

  protected EvaluationCaseEntity() {}

  public EvaluationCaseEntity(
      String id,
      String batchId,
      String caseCode,
      int sequenceNo,
      String status,
      String updatedAt) {
    this.id = id;
    this.batchId = batchId;
    this.caseCode = caseCode;
    this.sequenceNo = sequenceNo;
    this.status = status;
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

  public String getBatchId() {
    return batchId;
  }

  public String getCaseCode() {
    return caseCode;
  }

  public int getSequenceNo() {
    return sequenceNo;
  }

  public String getStatus() {
    return status;
  }

  public String getRunId() {
    return runId;
  }

  public String getExplanation() {
    return explanation;
  }

  public String getUpdatedAt() {
    return updatedAt;
  }
}
