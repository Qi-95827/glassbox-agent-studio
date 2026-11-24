package dev.workbench.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Eval Lab V2 批次：冻结实验配置、六维汇总与预算/耗时。 */
@Entity
@Table(name = "eval_lab_runs")
public class EvalLabRunEntity {
  @Id
  @Column(name = "id", length = 36)
  private String id;

  @Column(name = "owner_scope_key", nullable = false, length = 80)
  private String ownerScopeKey;

  @Column(name = "status", nullable = false, length = 30)
  private String status;

  @Column(name = "source_kind", nullable = false, length = 30)
  private String sourceKind;

  @Column(name = "source_key", length = 80)
  private String sourceKey;

  @JdbcTypeCode(SqlTypes.LONGVARCHAR)
  @Column(name = "categories_json", nullable = false)
  private String categoriesJson;

  @JdbcTypeCode(SqlTypes.LONGVARCHAR)
  @Column(name = "config_json", nullable = false)
  private String configJson;

  @Column(name = "config_sha256", nullable = false, length = 64)
  private String configSha256;

  @JdbcTypeCode(SqlTypes.LONGVARCHAR)
  @Column(name = "summary_json")
  private String summaryJson;

  @Column(name = "judge_enabled", nullable = false)
  private boolean judgeEnabled;

  @Column(name = "judge_connection_id", length = 36)
  private String judgeConnectionId;

  @Column(name = "total_cost_usd", nullable = false)
  private double totalCostUsd;

  @Column(name = "total_tokens", nullable = false)
  private int totalTokens;

  @Column(name = "total_duration_ms", nullable = false)
  private long totalDurationMs;

  @Column(name = "error_message", length = 1000)
  private String errorMessage;

  @Column(name = "locale", nullable = false, length = 10)
  private String locale;

  @Column(name = "created_at", nullable = false, length = 40)
  private String createdAt;

  @Column(name = "started_at", length = 40)
  private String startedAt;

  @Column(name = "finished_at", length = 40)
  private String finishedAt;

  protected EvalLabRunEntity() {}

  public EvalLabRunEntity(
      String id,
      String ownerScopeKey,
      String status,
      String sourceKind,
      String sourceKey,
      String categoriesJson,
      String configJson,
      String configSha256,
      boolean judgeEnabled,
      String judgeConnectionId,
      String locale,
      String createdAt) {
    this.id = id;
    this.ownerScopeKey = ownerScopeKey;
    this.status = status;
    this.sourceKind = sourceKind;
    this.sourceKey = sourceKey;
    this.categoriesJson = categoriesJson;
    this.configJson = configJson;
    this.configSha256 = configSha256;
    this.judgeEnabled = judgeEnabled;
    this.judgeConnectionId = judgeConnectionId;
    this.locale = locale;
    this.createdAt = createdAt;
  }

  public String getId() {
    return id;
  }

  public String getOwnerScopeKey() {
    return ownerScopeKey;
  }

  public String getStatus() {
    return status;
  }

  public String getSourceKind() {
    return sourceKind;
  }

  public String getSourceKey() {
    return sourceKey;
  }

  public String getCategoriesJson() {
    return categoriesJson;
  }

  public String getConfigJson() {
    return configJson;
  }

  public String getConfigSha256() {
    return configSha256;
  }

  public String getSummaryJson() {
    return summaryJson;
  }

  public boolean isJudgeEnabled() {
    return judgeEnabled;
  }

  public String getJudgeConnectionId() {
    return judgeConnectionId;
  }

  public double getTotalCostUsd() {
    return totalCostUsd;
  }

  public int getTotalTokens() {
    return totalTokens;
  }

  public long getTotalDurationMs() {
    return totalDurationMs;
  }

  public String getErrorMessage() {
    return errorMessage;
  }

  public String getLocale() {
    return locale;
  }

  public String getCreatedAt() {
    return createdAt;
  }

  public String getStartedAt() {
    return startedAt;
  }

  public String getFinishedAt() {
    return finishedAt;
  }

  public void start(String time) {
    this.status = "RUNNING";
    this.startedAt = time;
  }

  /** 逐题落库后同步累计值，让前端在运行中即可看到真实成本与耗时。 */
  public void progress(double cost, int tokens, long duration) {
    this.totalCostUsd = Math.max(0, cost);
    this.totalTokens = Math.max(0, tokens);
    this.totalDurationMs = Math.max(0, duration);
  }

  public void finish(
      String status, String summaryJson, double cost, int tokens, long duration, String error) {
    this.status = status;
    this.summaryJson = summaryJson;
    this.totalCostUsd = Math.max(0, cost);
    this.totalTokens = Math.max(0, tokens);
    this.totalDurationMs = Math.max(0, duration);
    this.errorMessage = error;
    this.finishedAt = timeNow();
  }

  private static String timeNow() {
    return dev.workbench.domain.WorkbenchTime.now();
  }
}
