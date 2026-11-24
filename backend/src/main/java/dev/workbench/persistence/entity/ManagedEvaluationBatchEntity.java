package dev.workbench.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 绑定实际数据集版本、评测配置与预算的批次。 */
@Entity
@Table(name = "managed_evaluation_batches")
public class ManagedEvaluationBatchEntity {
  @Id
  @Column(name = "id", length = 36)
  private String id;

  @Column(name = "dataset_key", nullable = false, length = 40)
  private String datasetKey;

  @Column(name = "dataset_version_id", nullable = false, length = 36)
  private String datasetVersionId;

  @Column(name = "definition_sha256", nullable = false, length = 64)
  private String definitionSha256;

  @JdbcTypeCode(SqlTypes.LONGVARCHAR)
  @Column(name = "config_json", nullable = false)
  private String configJson;

  @Column(name = "config_sha256", nullable = false, length = 64)
  private String configSha256;

  @Column(name = "status", nullable = false, length = 30)
  private String status;

  @Column(name = "created_at", nullable = false, length = 40)
  private String createdAt;

  @Column(name = "finished_at", length = 40)
  private String finishedAt;

  protected ManagedEvaluationBatchEntity() {}

  public ManagedEvaluationBatchEntity(
      String id,
      String datasetKey,
      String datasetVersionId,
      String definitionSha256,
      String configJson,
      String configSha256,
      String status,
      String createdAt) {
    this.id = id;
    this.datasetKey = datasetKey;
    this.datasetVersionId = datasetVersionId;
    this.definitionSha256 = definitionSha256;
    this.configJson = configJson;
    this.configSha256 = configSha256;
    this.status = status;
    this.createdAt = createdAt;
  }

  public String getId() {
    return id;
  }

  public String getDatasetKey() {
    return datasetKey;
  }

  public String getDatasetVersionId() {
    return datasetVersionId;
  }

  public String getDefinitionSha256() {
    return definitionSha256;
  }

  public String getConfigJson() {
    return configJson;
  }

  public String getConfigSha256() {
    return configSha256;
  }

  public String getStatus() {
    return status;
  }

  public String getCreatedAt() {
    return createdAt;
  }

  public String getFinishedAt() {
    return finishedAt;
  }

  public void finish(String status, String time) {
    this.status = status;
    this.finishedAt = time;
  }
}
