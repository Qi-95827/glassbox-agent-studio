package dev.workbench.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 外部动作每次对账的查询结果，未知结果也保留历史。 */
@Entity
@Table(name = "external_action_checks")
public class ExternalActionCheckEntity {
  @Id
  @Column(name = "id", length = 36)
  private String id;

  @Column(name = "action_id", nullable = false, length = 36)
  private String actionId;

  @Column(name = "outcome", nullable = false, length = 30)
  private String outcome;

  @Column(name = "evidence", length = 2000)
  private String evidence;

  @Column(name = "checked_at", nullable = false, length = 40)
  private String checkedAt;

  protected ExternalActionCheckEntity() {}

  public ExternalActionCheckEntity(
      String id, String actionId, String outcome, String evidence, String checkedAt) {
    this.id = id;
    this.actionId = actionId;
    this.outcome = outcome;
    this.evidence = evidence;
    this.checkedAt = checkedAt;
  }

  public String getId() {
    return id;
  }

  public String getActionId() {
    return actionId;
  }

  public String getOutcome() {
    return outcome;
  }

  public String getEvidence() {
    return evidence;
  }

  public String getCheckedAt() {
    return checkedAt;
  }
}
