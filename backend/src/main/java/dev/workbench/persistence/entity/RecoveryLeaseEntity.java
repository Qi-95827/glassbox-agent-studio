package dev.workbench.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 跨进程恢复租约；过期后允许新持有者接管，旧持有者仍须在写入时核对令牌。 */
@Entity
@Table(name = "recovery_leases")
public class RecoveryLeaseEntity {
  @Id
  @Column(name = "run_id", length = 36)
  private String runId;

  @Column(name = "lease_owner", nullable = false, length = 120)
  private String leaseOwner;

  @Column(name = "lease_token", nullable = false, length = 36)
  private String leaseToken;

  @Column(name = "acquired_at", nullable = false, length = 40)
  private String acquiredAt;

  @Column(name = "expires_at", nullable = false, length = 40)
  private String expiresAt;

  @Column(name = "status", nullable = false, length = 20)
  private String status;

  protected RecoveryLeaseEntity() {}

  public RecoveryLeaseEntity(
      String runId,
      String leaseOwner,
      String leaseToken,
      String acquiredAt,
      String expiresAt,
      String status) {
    this.runId = runId;
    this.leaseOwner = leaseOwner;
    this.leaseToken = leaseToken;
    this.acquiredAt = acquiredAt;
    this.expiresAt = expiresAt;
    this.status = status;
  }

  public String getRunId() {
    return runId;
  }

  public String getLeaseOwner() {
    return leaseOwner;
  }

  public String getLeaseToken() {
    return leaseToken;
  }

  public String getAcquiredAt() {
    return acquiredAt;
  }

  public String getExpiresAt() {
    return expiresAt;
  }

  public String getStatus() {
    return status;
  }

  public void acquire(String owner, String token, String acquired, String expires) {
    this.leaseOwner = owner;
    this.leaseToken = token;
    this.acquiredAt = acquired;
    this.expiresAt = expires;
    this.status = "ACTIVE";
  }

  public void expire() {
    this.status = "EXPIRED";
  }

  public void release() {
    this.status = "RELEASED";
  }
}
