package dev.workbench.observability;

import dev.workbench.domain.InvocationStatus;
import java.util.Map;

/** 单实例的同名工具调用汇总；已知耗时为空表示没有可统计值。 */
public record ToolMetrics(
    String name,
    long calls,
    Map<InvocationStatus, Long> statusCounts,
    long unknownStatusCount,
    Long knownDurationMs,
    long unknownDurationCount) {
  public ToolMetrics {
    statusCounts = Map.copyOf(statusCounts);
  }
}
