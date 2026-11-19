package dev.workbench.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 可管理数据集批次的单个样本结果，保留关联运行以便跳回流程图。 */
@Entity
@Table(name = "managed_evaluation_cases")
public class ManagedEvaluationCaseEntity {
  @Id
  @Column(name = "id", length = 36)
  private String id;

  @Column(name = "batch_id", nullable = false, length = 36)
  private String batchId;

  @Column(name = "sample_code", nullable = false, length = 40)
  private String sampleCode;

  @Column(name = "title", nullable = false, length = 120)
  private String title;

  @Column(name = "expected_status", nullable = false, length = 30)
  private String expectedStatus;

  @Column(name = "run_id", length = 36)
  private String runId;

  @Column(name = "status", nullable = false, length = 30)
  private String status;

  @Column(name = "explanation", length = 1000)
  private String explanation;

  @Column(name = "updated_at", nullable = false, length = 40)
  private String updatedAt;

  protected ManagedEvaluationCaseEntity() {}

  public ManagedEvaluationCaseEntity(
      String id,
      String batchId,
      String sampleCode,
      String title,
      String expectedStatus,
      String status,
      String updatedAt) {
    this.id = id;
    this.batchId = batchId;
    this.sampleCode = sampleCode;
    this.title = title;
    this.expectedStatus = expectedStatus;
    this.status = status;
    this.updatedAt = updatedAt;
  }

  public String getId() {
    return id;
  }

  public String getBatchId() {
    return batchId;
  }

  public String getSampleCode() {
    return sampleCode;
  }

  public String getTitle() {
    return title;
  }

  public String getExpectedStatus() {
    return expectedStatus;
  }

  public String getRunId() {
    return runId;
  }

  public String getStatus() {
    return status;
  }

  public String getExplanation() {
    return explanation;
  }

  public String getUpdatedAt() {
    return updatedAt;
  }

  public void finish(String status, String runId, String explanation, String time) {
    this.status = status;
    this.runId = runId;
    this.explanation = explanation;
    this.updatedAt = time;
  }
}
