package dev.workbench.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 保留每次补充请求及其最终答复。 */
@Entity
@Table(name = "run_supplements")
public class RunSupplementEntity {
  @Id
  @Column(name = "id", length = 36, nullable = false)
  private String id;

  @Column(name = "run_id", length = 36, nullable = false)
  private String runId;

  @Column(name = "node_id", length = 36, nullable = false)
  private String nodeId;

  @Column(name = "reason_code", length = 50, nullable = false)
  private String reasonCode;

  @Column(name = "question", length = 500, nullable = false)
  private String question;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.VARCHAR)
  @Column(name = "kind", length = 30, nullable = false)
  private Kind kind;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.VARCHAR)
  @Column(name = "status", length = 30, nullable = false)
  private Status status;

  @Column(name = "answer_path", length = 200)
  private String answerPath;

  @Column(name = "answer_sha256", length = 64)
  private String answerSha256;

  @Column(name = "task_version", nullable = false)
  private Integer taskVersion;

  @Column(name = "created_at", length = 40, nullable = false)
  private String createdAt;

  @Column(name = "decided_at", length = 40)
  private String decidedAt;

  protected RunSupplementEntity() {}

  public RunSupplementEntity(
      String id,
      String runId,
      String nodeId,
      String reasonCode,
      String question,
      int taskVersion,
      String createdAt) {
    this.id = id;
    this.runId = runId;
    this.nodeId = nodeId;
    this.reasonCode = reasonCode;
    this.question = question;
    this.kind = Kind.TEST_FILE;
    this.status = Status.OPEN;
    this.taskVersion = taskVersion;
    this.createdAt = createdAt;
  }

  public enum Kind {
    TEST_FILE
  }

  public enum Status {
    OPEN,
    APPLIED
  }
}
