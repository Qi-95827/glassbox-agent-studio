package dev.workbench.domain;

/** 首次运行的可选限额；null 表示没有配置该项上限。 */
public record RunLimits(
    Integer maxRounds,
    Integer maxTools,
    Integer maxPerTool,
    Long maxReportedTokens,
    Integer maxIdenticalTools,
    Integer maxSeconds,
    Integer maxAgentRounds,
    Integer maxAgentTools) {
  public RunLimits {
    if (outside(maxRounds, 1, 20)
        || outside(maxTools, 0, 20)
        || outside(maxPerTool, 0, 20)
        || outside(maxSeconds, 1, 300)) throw new IllegalArgumentException("运行限额超出允许范围。");
    if (maxReportedTokens != null && (maxReportedTokens < 1 || maxReportedTokens > 1_000_000))
      throw new IllegalArgumentException("已报告 Token 上限超出允许范围。");
    if (maxIdenticalTools != null && (maxIdenticalTools < 1 || maxIdenticalTools > 20))
      throw new IllegalArgumentException("连续相同工具上限超出允许范围。");
    if (maxAgentRounds != null && (maxAgentRounds < 1 || maxAgentRounds > 20))
      throw new IllegalArgumentException("单个 Agent 模型调用上限超出允许范围。");
    if (maxAgentTools != null && (maxAgentTools < 0 || maxAgentTools > 20))
      throw new IllegalArgumentException("单个 Agent 工具调用上限超出允许范围。");
  }

  private static boolean outside(Integer value, int minimum, int maximum) {
    return value != null && (value < minimum || value > maximum);
  }
}
