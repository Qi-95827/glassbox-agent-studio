package dev.workbench.persistence.entity;

import dev.workbench.domain.InvocationStatus;
import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 调用记录共享的主键、归属、状态和时间。 */
@MappedSuperclass
public abstract class InvocationEntity {
  @Id
  @Column(length = 36)
  private String id;

  @Column(name = "run_id", nullable = false, length = 36)
  private String runId;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.VARCHAR)
  @Column(nullable = false, length = 30)
  private InvocationStatus status;

  @Column(name = "created_at", nullable = false, length = 40)
  private String createdAt;

  @Column(name = "updated_at", nullable = false, length = 40)
  private String updatedAt;

  protected InvocationEntity() {}

  protected InvocationEntity(String id, String runId, String time) {
    this.id = id;
    this.runId = runId;
    this.status = InvocationStatus.RUNNING;
    this.createdAt = time;
    this.updatedAt = time;
  }
}
