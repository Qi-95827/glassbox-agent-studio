package dev.workbench.persistence.entity;

import dev.workbench.framework.workflow.check.SystemCheckStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "workflow_checks")
public class WorkflowCheckEntity {
  @Id
  @Column(name = "id", length = 36, nullable = false)
  private String id;

  @Column(name = "node_id", length = 36, nullable = false)
  private String nodeId;

  @Column(name = "attempt_no", nullable = false)
  private Integer attemptNo;

  @Column(name = "check_id", length = 64, nullable = false)
  private String checkId;

  @Column(name = "check_version", nullable = false)
  private Integer checkVersion;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.VARCHAR)
  @Column(name = "status", length = 30, nullable = false)
  private SystemCheckStatus status;

  @Column(name = "summary", length = 1000)
  private String summary;

  @Column(name = "evidence", length = 2000)
  private String evidence;

  @Column(name = "inputs_sha256", length = 64, nullable = false)
  private String inputsSha256;

  @Column(name = "started_at", length = 40, nullable = false)
  private String startedAt;

  @Column(name = "finished_at", length = 40)
  private String finishedAt;

  @Column(name = "external_evidence_id", length = 80)
  private String externalEvidenceId;

  protected WorkflowCheckEntity() {}

  public WorkflowCheckEntity(
      String id,
      String nodeId,
      Integer attemptNo,
      String checkId,
      Integer checkVersion,
      String inputsSha256,
      String startedAt) {
    this.id = id;
    this.nodeId = nodeId;
    this.attemptNo = attemptNo;
    this.checkId = checkId;
    this.checkVersion = checkVersion;
    this.inputsSha256 = inputsSha256;
    this.startedAt = startedAt;
    this.status = SystemCheckStatus.RUNNING;
  }
}
