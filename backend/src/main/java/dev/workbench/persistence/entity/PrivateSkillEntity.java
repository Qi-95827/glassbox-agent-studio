package dev.workbench.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

/** 用户私有 Skill 的可编辑草稿；发布会生成不可变版本。 */
@Entity
@Table(name = "private_skills")
public class PrivateSkillEntity implements Persistable<String> {
  @Transient private boolean isNew = true;

  @Id
  @Column(name = "id", length = 36)
  private String id;

  @Column(name = "owner_scope_key", nullable = false, length = 80)
  private String ownerScopeKey;

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

  @Column(name = "draft_sha256", nullable = false, length = 64)
  private String draftSha256;

  @Column(name = "status", nullable = false, length = 20)
  private String status;

  @Column(name = "current_version", nullable = false)
  private int currentVersion;

  @Column(name = "created_at", nullable = false, length = 40)
  private String createdAt;

  @Column(name = "updated_at", nullable = false, length = 40)
  private String updatedAt;

  @Column(name = "archived_at", length = 40)
  private String archivedAt;

  protected PrivateSkillEntity() {}

  public PrivateSkillEntity(String id, String ownerScopeKey, String name, String createdAt) {
    this.id = id;
    this.ownerScopeKey = ownerScopeKey;
    this.name = name;
    this.status = "DRAFT";
    this.createdAt = createdAt;
    this.updatedAt = createdAt;
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

  public String getOwnerScopeKey() {
    return ownerScopeKey;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getSummary() {
    return summary;
  }

  public void setSummary(String summary) {
    this.summary = summary;
  }

  public String getUsageNotes() {
    return usageNotes;
  }

  public void setUsageNotes(String usageNotes) {
    this.usageNotes = usageNotes;
  }

  public String getInputContract() {
    return inputContract;
  }

  public void setInputContract(String inputContract) {
    this.inputContract = inputContract;
  }

  public String getOutputContract() {
    return outputContract;
  }

  public void setOutputContract(String outputContract) {
    this.outputContract = outputContract;
  }

  public String getContentMd() {
    return contentMd;
  }

  public void setContentMd(String contentMd) {
    this.contentMd = contentMd;
  }

  public String getDraftSha256() {
    return draftSha256;
  }

  public void setDraftSha256(String draftSha256) {
    this.draftSha256 = draftSha256;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    this.status = status;
  }

  public int getCurrentVersion() {
    return currentVersion;
  }

  public void setCurrentVersion(int currentVersion) {
    this.currentVersion = currentVersion;
  }

  public String getCreatedAt() {
    return createdAt;
  }

  public String getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(String updatedAt) {
    this.updatedAt = updatedAt;
  }

  public String getArchivedAt() {
    return archivedAt;
  }

  public void setArchivedAt(String archivedAt) {
    this.archivedAt = archivedAt;
  }
}
