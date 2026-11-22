package dev.workbench.observability;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 类 Grafana 运行监控看板的只读聚合结果。
 *
 * <p>所有字段均为明确类型，空值用可空包装类型表达；比例字段由服务端统一计算，前端不得自行猜测分母。
 */
public record MonitoringDashboard(
    String range,
    Instant generatedAt,
    Summary summary,
    List<TimeSeriesPoint> timeSeries,
    Distributions distributions,
    Rankings rankings) {

  /** 顶部 KPI 概览。 */
  public record Summary(
      long totalRuns,
      long completedRuns,
      long deliveredRuns,
      long failedRuns,
      long blockedRuns,
      long anomalyRunCount,
      double successRate,
      Long avgRunDurationMs,
      Long p95RunDurationMs,
      long totalModelCalls,
      long totalInputTokens,
      long totalOutputTokens,
      BigDecimal estimatedCost,
      long totalToolCalls,
      long toolDenyCount,
      double toolDenyRate) {}

  /** 按小时或按天分桶的时序点；无样本的均值保持为空。 */
  public record TimeSeriesPoint(
      String bucketLabel,
      String bucketStart,
      long runCount,
      long successCount,
      long failedCount,
      Long avgDurationMs,
      long totalTokens) {}

  /** 圆盘 / Donut 图数据。 */
  public record Distributions(
      List<TaskStatusSlice> taskStatusBreakdown,
      List<ModelSlice> modelBreakdown,
      List<ToolSlice> toolBreakdown,
      List<GatewayDenySlice> gatewayDenyBreakdown) {}

  public record TaskStatusSlice(String status, long count, double percent) {}

  public record ModelSlice(
      String model, long calls, long totalTokens, Long avgLatencyMs, long failedCalls) {}

  public record ToolSlice(
      String tool, long calls, long passedCalls, long failedCalls, Long avgDurationMs) {}

  /** 高频拦截安检门；附带首个样本的运行与节点，便于一键跳转排查。 */
  public record GatewayDenySlice(
      String gate, String label, long count, String runId, String nodeId) {}

  /** 细粒度明细与 Top 排行。 */
  public record Rankings(
      List<RecentRun> recentRuns,
      List<SlowNode> slowestNodes,
      List<HighTokenRun> highTokenRuns) {}

  public record RecentRun(
      String runId,
      String taskTitle,
      String taskStatus,
      String runStatus,
      String stopCause,
      String model,
      int nodeCount,
      Long durationMs,
      long inputTokens,
      long outputTokens,
      long toolCalls,
      long toolDenies,
      boolean hasAnomaly,
      String createdAt) {}

  public record SlowNode(
      String runId,
      String nodeId,
      String nodeLabel,
      String role,
      String model,
      Long durationMs) {}

  public record HighTokenRun(
      String runId,
      String taskTitle,
      String model,
      long totalTokens,
      long inputTokens,
      long outputTokens) {}
}
