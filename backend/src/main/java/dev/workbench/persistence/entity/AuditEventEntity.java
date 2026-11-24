package dev.workbench.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

@Entity
@Table(name = "audit_events")
public class AuditEventEntity implements Persistable<String> {
  @Transient private boolean isNew = true;

  @Id
  @Column(name = "id", length = 36, nullable = false)
  private String id;

  @Column(name = "actor", length = 80, nullable = false)
  private String actor;

  @Column(name = "action", length = 60, nullable = false)
  private String action;

  @Column(name = "target_type", length = 40, nullable = false)
  private String targetType;

  @Column(name = "target_id", length = 80)
  private String targetId;

  @Column(name = "outcome", length = 20, nullable = false)
  private String outcome;

  @Column(name = "detail", length = 1000)
  private String detail;

  @Column(name = "created_at", length = 40, nullable = false)
  private String createdAt;

  protected AuditEventEntity() {}

  public AuditEventEntity(
      String id,
      String actor,
      String action,
      String targetType,
      String targetId,
      String outcome,
      String detail,
      String createdAt) {
    this.id = id;
    this.actor = actor;
    this.action = action;
    this.targetType = targetType;
    this.targetId = targetId;
    this.outcome = outcome;
    this.detail = detail;
    this.createdAt = createdAt;
  }

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
}
