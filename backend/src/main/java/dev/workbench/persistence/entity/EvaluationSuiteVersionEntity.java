package dev.workbench.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.hibernate.annotations.Immutable;
import org.springframework.data.domain.Persistable;

/** 不可变评测套件版本；发布后新增新版本，不覆盖历史定义。 */
@Entity
@Immutable
@Table(name = "evaluation_suite_versions")
public class EvaluationSuiteVersionEntity implements Persistable<String> {
  @Transient private boolean isNew = true;

  @Id
  @Column(name = "id", length = 36, nullable = false)
  private String id;

  @Column(name = "suite", length = 40, nullable = false)
  private String suite;

  @Column(name = "version", nullable = false)
  private int version;

  @Lob
  @Column(name = "definition_json", nullable = false)
  private String definitionJson;

  @Column(name = "definition_sha256", length = 64, nullable = false)
  private String definitionSha256;

  @Column(name = "active", nullable = false)
  private boolean active;

  @Column(name = "created_at", length = 40, nullable = false)
  private String createdAt;

  protected EvaluationSuiteVersionEntity() {}

  public EvaluationSuiteVersionEntity(
      String id,
      String suite,
      int version,
      String definitionJson,
      String definitionSha256,
      boolean active,
      String createdAt) {
    this.id = id;
    this.suite = suite;
    this.version = version;
    this.definitionJson = definitionJson;
    this.definitionSha256 = definitionSha256;
    this.active = active;
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

  public String getSuite() {
    return suite;
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

  public boolean isActive() {
    return active;
  }

  public String getCreatedAt() {
    return createdAt;
  }
}
