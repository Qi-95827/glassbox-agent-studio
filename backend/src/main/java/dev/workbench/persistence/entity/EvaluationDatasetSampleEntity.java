package dev.workbench.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 数据集草稿样本；发布前可编辑，发布后只进入不可变版本快照。 */
@Entity
@Table(name = "evaluation_dataset_samples")
public class EvaluationDatasetSampleEntity {
  @Id
  @Column(name = "id", length = 36)
  private String id;

  @Column(name = "dataset_key", nullable = false, length = 40)
  private String datasetKey;

  @Column(name = "code", nullable = false, length = 40)
  private String code;

  @Column(name = "title", nullable = false, length = 120)
  private String title;

  @Column(name = "goal", nullable = false, length = 500)
  private String goal;

  @Column(name = "scenario", nullable = false, length = 20)
  private String scenario;

  @Column(name = "expected_status", nullable = false, length = 30)
  private String expectedStatus;

  @Column(name = "max_rounds", nullable = false)
  private int maxRounds;

  @Column(name = "max_tools", nullable = false)
  private int maxTools;

  @Column(name = "source_kind", nullable = false, length = 30)
  private String sourceKind;

  @Column(name = "source_ref", length = 120)
  private String sourceRef;

  @Column(name = "redacted", nullable = false)
  private boolean redacted;

  @Column(name = "version", nullable = false)
  private int version;

  @Column(name = "created_at", nullable = false, length = 40)
  private String createdAt;

  @Column(name = "updated_at", nullable = false, length = 40)
  private String updatedAt;

  protected EvaluationDatasetSampleEntity() {}

  public EvaluationDatasetSampleEntity(
      String id,
      String datasetKey,
      String code,
      String title,
      String goal,
      String scenario,
      String expectedStatus,
      int maxRounds,
      int maxTools,
      String sourceKind,
      String sourceRef,
      boolean redacted,
      int version,
      String createdAt,
      String updatedAt) {
    this.id = id;
    this.datasetKey = datasetKey;
    this.code = code;
    this.title = title;
    this.goal = goal;
    this.scenario = scenario;
    this.expectedStatus = expectedStatus;
    this.maxRounds = maxRounds;
    this.maxTools = maxTools;
    this.sourceKind = sourceKind;
    this.sourceRef = sourceRef;
    this.redacted = redacted;
    this.version = version;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
  }

  public String getId() {
    return id;
  }

  public String getDatasetKey() {
    return datasetKey;
  }

  public String getCode() {
    return code;
  }

  public String getTitle() {
    return title;
  }

  public String getGoal() {
    return goal;
  }

  public String getScenario() {
    return scenario;
  }

  public String getExpectedStatus() {
    return expectedStatus;
  }

  public int getMaxRounds() {
    return maxRounds;
  }

  public int getMaxTools() {
    return maxTools;
  }

  public String getSourceKind() {
    return sourceKind;
  }

  public String getSourceRef() {
    return sourceRef;
  }

  public boolean isRedacted() {
    return redacted;
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

  public void update(
      String title,
      String goal,
      String scenario,
      String expectedStatus,
      int maxRounds,
      int maxTools,
      int version,
      String time) {
    this.title = title;
    this.goal = goal;
    this.scenario = scenario;
    this.expectedStatus = expectedStatus;
    this.maxRounds = maxRounds;
    this.maxTools = maxTools;
    this.version = version;
    this.updatedAt = time;
  }
}
