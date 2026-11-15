package dev.workbench.observability;

import java.util.List;
import java.util.UUID;

/** 基于已保存调用记录的实例统计；时长为包含等待和暂停的墙钟区间，未知值使用 null。 */
public record AgentMetrics(
    UUID agentId,
    String roleName,
    Long durationMs,
    long modelCalls,
    long modelDurationKnownMs,
    long modelDurationUnknownCount,
    Long knownInputTokens,
    long unknownInputCount,
    Long knownOutputTokens,
    long unknownOutputCount,
    long toolCalls,
    long toolDurationKnownMs,
    long toolDurationUnknownCount,
    List<String> toolNames,
    List<ToolMetrics> toolBreakdown) {
  public AgentMetrics {
    toolNames = List.copyOf(toolNames);
    toolBreakdown = List.copyOf(toolBreakdown);
  }
}
