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

/** 四类入口的交付物、生效验收条件和提示。主键即 task_id，一个任务一份。 */
@Entity
@Table(name = "task_entry_policies")
public class EntryPolicyEntity implements Persistable<String> {
  @Transient private boolean isNew = true;
  @Id
  @Column(name = "task_id", length = 36)
  private String taskId;

  @Column(name = "policy_version", nullable = false)
  private int policyVersion;

  @Lob
  @Column(name = "deliverables_json", nullable = false)
  private String deliverablesJson;

  @Lob
  @Column(name = "acceptance_json", nullable = false)
  private String acceptanceJson;

  @Lob
  @Column(name = "warnings_json")
  private String warningsJson;

  @Column(name = "created_at", nullable = false, length = 40)
  private String createdAt;

  protected EntryPolicyEntity() {}

  public EntryPolicyEntity(
      String taskId,
      int policyVersion,
      String deliverablesJson,
      String acceptanceJson,
      String warningsJson,
      String createdAt) {
    this.taskId = taskId;
    this.policyVersion = policyVersion;
    this.deliverablesJson = deliverablesJson;
    this.acceptanceJson = acceptanceJson;
    this.warningsJson = warningsJson;
    this.createdAt = createdAt;
  }

  public String getTaskId() {
    return taskId;
  }

  @Override
  public String getId() {
    return taskId;
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

  public int getPolicyVersion() {
    return policyVersion;
  }

  public String getDeliverablesJson() {
    return deliverablesJson;
  }

  public String getAcceptanceJson() {
    return acceptanceJson;
  }

  public String getWarningsJson() {
    return warningsJson;
  }

  public String getCreatedAt() {
    return createdAt;
  }
}