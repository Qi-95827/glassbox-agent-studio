package dev.workbench.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

/** 自定义评测集的单个样本；setup / assertions / rubric 以 JSON 原样冻结。 */
@Entity
@Table(name = "eval_custom_samples")
public class EvalCustomSampleEntity {
  @Id
  @Column(name = "id", length = 36)
  private String id;

  @Column(name = "dataset_id", nullable = false, length = 36)
  private String datasetId;

  @Column(name = "sample_key", nullable = false, length = 80)
  private String sampleKey;

  @Column(name = "title_zh", length = 200)
  private String titleZh;

  @Column(name = "title_en", length = 200)
  private String titleEn;

  @Lob
  @Column(name = "prompt", nullable = false)
  private String prompt;

  @Lob
  @Column(name = "setup_json", nullable = false)
  private String setupJson;

  @Lob
  @Column(name = "assertions_json", nullable = false)
  private String assertionsJson;

  @Lob
  @Column(name = "rubric_json", nullable = false)
  private String rubricJson;

  @Column(name = "created_at", nullable = false, length = 40)
  private String createdAt;

  @Column(name = "updated_at", nullable = false, length = 40)
  private String updatedAt;

  protected EvalCustomSampleEntity() {}

  public EvalCustomSampleEntity(
      String id,
      String datasetId,
      String sampleKey,
      String titleZh,
      String titleEn,
      String prompt,
      String setupJson,
      String assertionsJson,
      String rubricJson,
      String createdAt,
      String updatedAt) {
    this.id = id;
    this.datasetId = datasetId;
    this.sampleKey = sampleKey;
    this.titleZh = titleZh;
    this.titleEn = titleEn;
    this.prompt = prompt;
    this.setupJson = setupJson;
    this.assertionsJson = assertionsJson;
    this.rubricJson = rubricJson;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
  }

  public String getId() {
    return id;
  }

  public String getDatasetId() {
    return datasetId;
  }

  public String getSampleKey() {
    return sampleKey;
  }

  public String getTitleZh() {
    return titleZh;
  }

  public String getTitleEn() {
    return titleEn;
  }

  public String getPrompt() {
    return prompt;
  }

  public String getSetupJson() {
    return setupJson;
  }

  public String getAssertionsJson() {
    return assertionsJson;
  }

  public String getRubricJson() {
    return rubricJson;
  }

  public String getCreatedAt() {
    return createdAt;
  }

  public String getUpdatedAt() {
    return updatedAt;
  }

  public void update(
      String titleZh,
      String titleEn,
      String prompt,
      String setupJson,
      String assertionsJson,
      String rubricJson,
      String at) {
    this.titleZh = titleZh;
    this.titleEn = titleEn;
    this.prompt = prompt;
    this.setupJson = setupJson;
    this.assertionsJson = assertionsJson;
    this.rubricJson = rubricJson;
    this.updatedAt = at;
  }
}
