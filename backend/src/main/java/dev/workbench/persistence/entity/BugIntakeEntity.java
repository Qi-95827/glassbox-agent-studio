package dev.workbench.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

/** 模拟 Bug 的原始描述、来源版本、提交序号、重复链和分诊结论。 */
@Entity
@Table(name = "bug_intakes")
public class BugIntakeEntity implements Persistable<String> {
  @Transient private boolean isNew = true;

  @Id
  @Column(name = "id", length = 36, nullable = false)
  private String id;

  @Column(name = "bug_id", length = 80, nullable = false)
  private String bugId;

  @Column(name = "source", length = 80, nullable = false)
  private String source;

  @Column(name = "source_version", nullable = false)
  private int sourceVersion;

  @Lob
  @Column(name = "original_description", nullable = false)
  private String originalDescription;

  @Lob
  @Column(name = "bug_description", nullable = false)
  private String bugDescription;

  @Lob
  @Column(name = "expected_symptom", nullable = false)
  private String expectedSymptom;

  @Column(name = "expected_test_id", length = 300, nullable = false)
  private String expectedTestId;

  @Column(name = "content_sha256", length = 64, nullable = false)
  private String contentSha256;

  @Column(name = "workspace_id", length = 36)
  private String workspaceId;

  @Column(name = "task_id", length = 36, nullable = false)
  private String taskId;

  @Column(name = "run_id", length = 36, nullable = false)
  private String runId;

  @Column(name = "submission_seq", nullable = false)
  private int submissionSeq;

  @Column(name = "duplicate_of", length = 36)
  private String duplicateOf;

  @Column(name = "previous_intake_id", length = 36)
  private String previousIntakeId;

  @Column(name = "triage_status", length = 30, nullable = false)
  private String triageStatus;

  @Column(name = "triage_reason", length = 1000)
  private String triageReason;

  @Column(name = "triage_check_id", length = 36)
  private String triageCheckId;

  @Column(name = "triage_updated_at", length = 40)
  private String triageUpdatedAt;

  @Column(name = "regression_status", length = 30)
  private String regressionStatus;

  @Column(name = "regression_check_id", length = 36)
  private String regressionCheckId;

  @Column(name = "regression_updated_at", length = 40)
  private String regressionUpdatedAt;

  @Column(name = "created_at", length = 40, nullable = false)
  private String createdAt;

  protected BugIntakeEntity() {}

  public BugIntakeEntity(
      String id,
      String bugId,
      String source,
      int sourceVersion,
      String originalDescription,
      String bugDescription,
      String expectedSymptom,
      String expectedTestId,
      String contentSha256,
      String workspaceId,
      String taskId,
      String runId,
      int submissionSeq,
      String duplicateOf,
      String previousIntakeId,
      String triageStatus,
      String createdAt) {
    this.id = id;
    this.bugId = bugId;
    this.source = source;
    this.sourceVersion = sourceVersion;
    this.originalDescription = originalDescription;
    this.bugDescription = bugDescription;
    this.expectedSymptom = expectedSymptom;
    this.expectedTestId = expectedTestId;
    this.contentSha256 = contentSha256;
    this.workspaceId = workspaceId;
    this.taskId = taskId;
    this.runId = runId;
    this.submissionSeq = submissionSeq;
    this.duplicateOf = duplicateOf;
    this.previousIntakeId = previousIntakeId;
    this.triageStatus = triageStatus;
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

  public String getBugId() {
    return bugId;
  }

  public String getSource() {
    return source;
  }

  public int getSourceVersion() {
    return sourceVersion;
  }

  public String getOriginalDescription() {
    return originalDescription;
  }

  public String getBugDescription() {
    return bugDescription;
  }

  public String getExpectedSymptom() {
    return expectedSymptom;
  }

  public String getExpectedTestId() {
    return expectedTestId;
  }

  public String getContentSha256() {
    return contentSha256;
  }

  public String getWorkspaceId() {
    return workspaceId;
  }

  public String getTaskId() {
    return taskId;
  }

  public String getRunId() {
    return runId;
  }

  public int getSubmissionSeq() {
    return submissionSeq;
  }

  public String getDuplicateOf() {
    return duplicateOf;
  }

  public String getPreviousIntakeId() {
    return previousIntakeId;
  }

  public String getTriageStatus() {
    return triageStatus;
  }

  public String getTriageReason() {
    return triageReason;
  }

  public String getTriageCheckId() {
    return triageCheckId;
  }

  public String getTriageUpdatedAt() {
    return triageUpdatedAt;
  }

  public String getRegressionStatus() {
    return regressionStatus;
  }

  public String getRegressionCheckId() {
    return regressionCheckId;
  }

  public String getRegressionUpdatedAt() {
    return regressionUpdatedAt;
  }

  public String getCreatedAt() {
    return createdAt;
  }
}
