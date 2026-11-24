package dev.workbench.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 运行级硬费用预算与已预留/已结算金额。 */
@Entity
@Table(name = "run_cost_limits")
public class RunCostLimitEntity {
  @Id
  @Column(name = "run_id", length = 36)
  private String runId;

  @Column(name = "max_cost_micros", nullable = false)
  private long maxCostMicros;

  @Column(name = "price_version_id", nullable = false, length = 36)
  private String priceVersionId;

  /** 确认时冻结的 modelCode -> priceVersionId；旧运行为空时回退到 priceVersionId。 */
  @Column(name = "model_price_versions_json", length = 4000)
  private String modelPriceVersionsJson;

  @Column(name = "reserved_micros", nullable = false)
  private long reservedMicros;

  @Column(name = "settled_micros", nullable = false)
  private long settledMicros;

  @Column(name = "unknown_calls", nullable = false)
  private int unknownCalls;

  @Column(name = "overage_micros", nullable = false)
  private long overageMicros;

  @Column(name = "created_at", nullable = false, length = 40)
  private String createdAt;

  @Column(name = "updated_at", nullable = false, length = 40)
  private String updatedAt;

  protected RunCostLimitEntity() {}

  public RunCostLimitEntity(
      String runId, long maxCostMicros, String priceVersionId, String createdAt, String updatedAt) {
    this(runId, maxCostMicros, priceVersionId, null, createdAt, updatedAt);
  }

  public RunCostLimitEntity(
      String runId,
      long maxCostMicros,
      String priceVersionId,
      String modelPriceVersionsJson,
      String createdAt,
      String updatedAt) {
    this.runId = runId;
    this.maxCostMicros = maxCostMicros;
    this.priceVersionId = priceVersionId;
    this.modelPriceVersionsJson = modelPriceVersionsJson;
    this.reservedMicros = 0;
    this.settledMicros = 0;
    this.unknownCalls = 0;
    this.overageMicros = 0;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
  }

  public String getRunId() {
    return runId;
  }

  public long getMaxCostMicros() {
    return maxCostMicros;
  }

  public String getPriceVersionId() {
    return priceVersionId;
  }

  public String getModelPriceVersionsJson() {
    return modelPriceVersionsJson;
  }

  public long getReservedMicros() {
    return reservedMicros;
  }

  public long getSettledMicros() {
    return settledMicros;
  }

  public int getUnknownCalls() {
    return unknownCalls;
  }

  public long getOverageMicros() {
    return overageMicros;
  }

  public String getCreatedAt() {
    return createdAt;
  }

  public String getUpdatedAt() {
    return updatedAt;
  }

  public void reserve(long micros, String time) {
    this.reservedMicros = Math.addExact(this.reservedMicros, micros);
    this.updatedAt = time;
  }

  public void settle(long reserved, long actual, boolean unknown, String time) {
    if (unknown) {
      // 未知费用不能当作零；保留原预留金额并累计未知调用，直到人工核对。
      this.unknownCalls++;
    } else {
      this.reservedMicros = Math.max(0, Math.subtractExact(this.reservedMicros, reserved));
      this.settledMicros = Math.addExact(this.settledMicros, actual);
      this.overageMicros = Math.max(0, this.settledMicros - this.maxCostMicros);
    }
    this.updatedAt = time;
  }

  public void release(long reserved, String time) {
    this.reservedMicros = Math.max(0, Math.subtractExact(this.reservedMicros, reserved));
    this.updatedAt = time;
  }
}
