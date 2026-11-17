package dev.workbench.task;

/** 已在任务入口校验的首运行限制；可选限制为 null 表示未设置。 */
public record RunLimits(
    int maxRounds,
    int maxTools,
    int maxPerTool,
    Long maxReportedTokens,
    Integer maxIdenticalTools,
    int maxSeconds,
    Integer maxAgentRounds,
    Integer maxAgentTools) {
  public RunLimits {
    if (maxRounds < 1
        || maxRounds > 20
        || maxTools < 0
        || maxTools > 20
        || maxPerTool < 0
        || maxPerTool > 20
        || maxSeconds < 1
        || maxSeconds > 300) throw new IllegalArgumentException("运行限额超出允许范围。");
    if (maxReportedTokens != null && (maxReportedTokens < 1 || maxReportedTokens > 1_000_000))
      throw new IllegalArgumentException("已报告 Token 上限超出允许范围。");
    if (maxIdenticalTools != null && (maxIdenticalTools < 1 || maxIdenticalTools > 20))
      throw new IllegalArgumentException("连续相同工具上限超出允许范围。");
    if (maxAgentRounds != null && (maxAgentRounds < 1 || maxAgentRounds > 20))
      throw new IllegalArgumentException("单个 Agent 模型调用上限超出允许范围。");
    if (maxAgentTools != null && (maxAgentTools < 0 || maxAgentTools > 20))
      throw new IllegalArgumentException("单个 Agent 工具调用上限超出允许范围。");
  }
}
