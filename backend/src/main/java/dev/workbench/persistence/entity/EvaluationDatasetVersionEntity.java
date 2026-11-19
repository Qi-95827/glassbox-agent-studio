package dev.workbench.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

/** 已发布的不可变数据集版本。 */
@Entity
@Table(name = "evaluation_dataset_versions")
public class EvaluationDatasetVersionEntity {
  @Id
  @Column(name = "id", length = 36)
  private String id;

  @Column(name = "dataset_key", nullable = false, length = 40)
  private String datasetKey;

  @Column(name = "version", nullable = false)
  private int version;

  @Lob
  @Column(name = "definition_json", nullable = false)
  private String definitionJson;

  @Column(name = "definition_sha256", nullable = false, length = 64)
  private String definitionSha256;

  @Column(name = "sample_count", nullable = false)
  private int sampleCount;

  @Column(name = "published_by", nullable = false, length = 80)
  private String publishedBy;

  @Column(name = "created_at", nullable = false, length = 40)
  private String createdAt;

  protected EvaluationDatasetVersionEntity() {}

  public EvaluationDatasetVersionEntity(
      String id,
      String datasetKey,
      int version,
      String definitionJson,
      String definitionSha256,
      int sampleCount,
      String publishedBy,
      String createdAt) {
    this.id = id;
    this.datasetKey = datasetKey;
    this.version = version;
    this.definitionJson = definitionJson;
    this.definitionSha256 = definitionSha256;
    this.sampleCount = sampleCount;
    this.publishedBy = publishedBy;
    this.createdAt = createdAt;
  }

  public String getId() {
    return id;
  }

  public String getDatasetKey() {
    return datasetKey;
  }

  public int getVersion() {
    return version;
  }

  public String getDefinitionJson() {
    return definitionJson;
  }

  public String getDefinitionSha256() {
    return definitionSha256;
  }

  public int getSampleCount() {
    return sampleCount;
  }

  public String getPublishedBy() {
    return publishedBy;
  }

  public String getCreatedAt() {
    return createdAt;
  }
}
