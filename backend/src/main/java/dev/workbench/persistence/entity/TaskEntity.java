package dev.workbench.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

/** 任务表映射，初始版本由数据库设置，补充信息时单独更新。 */
@Entity
@Table(name = "tasks")
public class TaskEntity implements Persistable<String> {
  @Transient private boolean isNew = true;
  @Id
  @Column(name = "id", length = 36)
  private String id;

  @Column(name = "goal", nullable = false, length = 500)
  private String goal;

  @Column(name = "scenario", nullable = false, length = 20)
  private String scenario;

  @Column(name = "created_at", nullable = false, length = 40)
  private String createdAt;

  @Column(name = "archived_at", length = 40, insertable = false)
  private String archivedAt;

  @Column(name = "version", nullable = false, insertable = false)
  private int version;

  @Column(name = "model_mode", nullable = false, length = 20)
  private String modelMode;

  @Column(name = "workspace_id", length = 36)
  private String workspaceId;

  @Column(name = "file_scope", length = 2000)
  private String fileScope;

  @Column(name = "write_enabled", nullable = false)
  private boolean writeEnabled;

  @Column(name = "bug_id", length = 80)
  private String bugId;

  @Column(name = "memory_enabled", nullable = false)
  private boolean memoryEnabled;

  @Column(name = "constraints_json", nullable = false, length = 20000)
  private String constraintsJson;

  @Column(name = "acceptance_json", nullable = false, length = 20000)
  private String acceptanceJson;

  @Column(name = "entry_kind", nullable = false, length = 30)
  private String entryKind;

  @Column(name = "file_scopes_json", nullable = false, length = 20000)
  private String fileScopesJson;

  @Column(name = "model_connection_id", length = 36)
  private String modelConnectionId;

  /** 任务创建时的语言偏好，驱动 Prompt、输出语言与诊断文案。 */
  @Column(name = "locale", nullable = false, length = 16)
  private String locale;

  public TaskEntity() {}

  @Override
  public boolean isNew() {
    return isNew;
  }

  @PostLoad
  @PostPersist
  void markNotNew() {
    isNew = false;
  }

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  public String getGoal() {
    return goal;
  }

  public void setGoal(String goal) {
    this.goal = goal;
  }

  public String getScenario() {
    return scenario;
  }

  public void setScenario(String scenario) {
    this.scenario = scenario;
  }

  public String getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(String createdAt) {
    this.createdAt = createdAt;
  }

  public String getModelMode() {
    return modelMode;
  }

  public void setModelMode(String modelMode) {
    this.modelMode = modelMode;
  }

  public String getWorkspaceId() {
    return workspaceId;
  }

  public void setWorkspaceId(String workspaceId) {
    this.workspaceId = workspaceId;
  }

  public String getFileScope() {
    return fileScope;
  }

  public void setFileScope(String fileScope) {
    this.fileScope = fileScope;
  }

  public boolean isWriteEnabled() {
    return writeEnabled;
  }

  public void setWriteEnabled(boolean writeEnabled) {
    this.writeEnabled = writeEnabled;
  }

  public String getBugId() {
    return bugId;
  }

  public void setBugId(String bugId) {
    this.bugId = bugId;
  }

  public boolean isMemoryEnabled() {
    return memoryEnabled;
  }

  public void setMemoryEnabled(boolean memoryEnabled) {
    this.memoryEnabled = memoryEnabled;
  }

  public String getConstraintsJson() {
    return constraintsJson;
  }

  public void setConstraintsJson(String constraintsJson) {
    this.constraintsJson = constraintsJson;
  }

  public String getAcceptanceJson() {
    return acceptanceJson;
  }

  public void setAcceptanceJson(String acceptanceJson) {
    this.acceptanceJson = acceptanceJson;
  }

  public String getEntryKind() {
    return entryKind;
  }

  public void setEntryKind(String entryKind) {
    this.entryKind = entryKind;
  }

  public String getFileScopesJson() {
    return fileScopesJson;
  }

  public void setFileScopesJson(String fileScopesJson) {
    this.fileScopesJson = fileScopesJson;
  }

  public String getModelConnectionId() {
    return modelConnectionId;
  }

  public void setModelConnectionId(String modelConnectionId) {
    this.modelConnectionId = modelConnectionId;
  }

  public String getLocale() {
    return locale;
  }

  public void setLocale(String locale) {
    this.locale = locale;
  }
}