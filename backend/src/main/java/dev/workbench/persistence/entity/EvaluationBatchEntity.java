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

/** 评测批次；套件定义快照和实际预算配置随行保存。 */
@Entity
@Table(name = "evaluation_batches")
public class EvaluationBatchEntity implements Persistable<String> {
  @Transient private boolean isNew = true;

  @Id
  @Column(name = "id", length = 36, nullable = false)
  private String id;

  @Column(name = "suite", length = 40, nullable = false)
  private String suite;

  @Column(name = "status", length = 30, nullable = false)
  private String status;

  @Column(name = "created_at", length = 40, nullable = false)
  private String createdAt;

  @Column(name = "finished_at", length = 40)
  private String finishedAt;

  @Lob
  @Column(name = "definition_json")
  private String definitionJson;

  @Column(name = "definition_sha256", length = 64)
  private String definitionSha256;

  @Column(name = "suite_version")
  private Integer suiteVersion;

  @Lob
  @Column(name = "config_json")
  private String configJson;

  @Column(name = "config_sha256", length = 64)
  private String configSha256;

  @Column(name = "requested_by", length = 80)
  private String requestedBy;

  protected EvaluationBatchEntity() {}

  public EvaluationBatchEntity(
      String id,
      String suite,
      String status,
      String createdAt,
      String definitionJson,
      String definitionSha256,
      Integer suiteVersion,
      String configJson,
      String configSha256,
      String requestedBy) {
    this.id = id;
    this.suite = suite;
    this.status = status;
    this.createdAt = createdAt;
    this.definitionJson = definitionJson;
    this.definitionSha256 = definitionSha256;
    this.suiteVersion = suiteVersion;
    this.configJson = configJson;
    this.configSha256 = configSha256;
    this.requestedBy = requestedBy;
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

  public String getSuite() {
    return suite;
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

  public String getDefinitionJson() {
    return definitionJson;
  }

  public String getDefinitionSha256() {
    return definitionSha256;
  }

  public Integer getSuiteVersion() {
    return suiteVersion;
  }

  public String getConfigJson() {
    return configJson;
  }

  public String getConfigSha256() {
    return configSha256;
  }

  public String getRequestedBy() {
    return requestedBy;
  }
}
