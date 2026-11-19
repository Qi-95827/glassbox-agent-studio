package dev.workbench.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 单次模型调用的费用预留与结算。 */
@Entity
@Table(name = "cost_reservations")
public class CostReservationEntity {
  @Id
  @Column(name = "id", length = 36)
  private String id;

  @Column(name = "run_id", nullable = false, length = 36)
  private String runId;

  @Column(name = "reserved_micros", nullable = false)
  private long reservedMicros;

  @Column(name = "settled_micros")
  private Long settledMicros;

  @Column(name = "over_budget_micros", nullable = false)
  private long overBudgetMicros;

  @Column(name = "status", nullable = false, length = 20)
  private String status;

  @Column(name = "created_at", nullable = false, length = 40)
  private String createdAt;

  @Column(name = "updated_at", nullable = false, length = 40)
  private String updatedAt;

  protected CostReservationEntity() {}

  public CostReservationEntity(
      String id,
      String runId,
      long reservedMicros,
      String status,
      String createdAt,
      String updatedAt) {
    this.id = id;
    this.runId = runId;
    this.reservedMicros = reservedMicros;
    this.status = status;
    this.overBudgetMicros = 0;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
  }

  public String getId() {
    return id;
  }

  public String getRunId() {
    return runId;
  }

  public long getReservedMicros() {
    return reservedMicros;
  }

  public Long getSettledMicros() {
    return settledMicros;
  }

  public long getOverBudgetMicros() {
    return overBudgetMicros;
  }

  public String getStatus() {
    return status;
  }

  public String getCreatedAt() {
    return createdAt;
  }

  public String getUpdatedAt() {
    return updatedAt;
  }

  public void settle(Long actual, boolean unknown, long overBudget, String time) {
    this.settledMicros = actual;
    this.overBudgetMicros = overBudget;
    this.status = unknown ? "UNKNOWN" : overBudget > 0 ? "OVER_BUDGET" : "SETTLED";
    this.updatedAt = time;
  }

  public void cancel(String time) {
    this.status = "CANCELLED";
    this.updatedAt = time;
  }
}
