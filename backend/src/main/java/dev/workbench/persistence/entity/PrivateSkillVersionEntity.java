package dev.workbench.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;

/** Skill 的不可变发布版本；正文摘要参与 Agent 发布摘要。 */
@Entity
@Table(name = "private_skill_versions")
@IdClass(PrivateSkillVersionEntity.Key.class)
public class PrivateSkillVersionEntity {
  @Id
  @Column(name = "skill_id", length = 36)
  private String skillId;

  @Id
  @Column(name = "version")
  private int version;

  @Column(name = "name", nullable = false, length = 120)
  private String name;

  @Column(name = "summary", nullable = false, length = 300)
  private String summary;

  @Column(name = "usage_notes", length = 2000)
  private String usageNotes;

  @Column(name = "input_contract", length = 1000)
  private String inputContract;

  @Column(name = "output_contract", length = 1000)
  private String outputContract;

  @Column(name = "content_md", nullable = false, columnDefinition = "CLOB")
  private String contentMd;

  @Column(name = "content_sha256", nullable = false, length = 64)
  private String contentSha256;

  @Column(name = "created_at", nullable = false, length = 40)
  private String createdAt;

  protected PrivateSkillVersionEntity() {}

  public PrivateSkillVersionEntity(
      String skillId,
      int version,
      String name,
      String summary,
      String usageNotes,
      String inputContract,
      String outputContract,
      String contentMd,
      String contentSha256,
      String createdAt) {
    this.skillId = skillId;
    this.version = version;
    this.name = name;
    this.summary = summary;
    this.usageNotes = usageNotes;
    this.inputContract = inputContract;
    this.outputContract = outputContract;
    this.contentMd = contentMd;
    this.contentSha256 = contentSha256;
    this.createdAt = createdAt;
  }

  public String getSkillId() {
    return skillId;
  }

  public int getVersion() {
    return version;
  }

  public String getName() {
    return name;
  }

  public String getSummary() {
    return summary;
  }

  public String getUsageNotes() {
    return usageNotes;
  }

  public String getInputContract() {
    return inputContract;
  }

  public String getOutputContract() {
    return outputContract;
  }

  public String getContentMd() {
    return contentMd;
  }

  public String getContentSha256() {
    return contentSha256;
  }

  public String getCreatedAt() {
    return createdAt;
  }

  public static class Key implements Serializable {
    private String skillId;
    private int version;

    public Key() {}

    public Key(String skillId, int version) {
      this.skillId = skillId;
      this.version = version;
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof Key key
          && Objects.equals(skillId, key.skillId)
          && version == key.version;
    }

    @Override
    public int hashCode() {
      return Objects.hash(skillId, version);
    }
  }
}
