package dev.workbench.evaluation;

/** 用户可配置的评测预算；只允许收紧样本预算，不能绕过模板授权。 */
public record EvaluationConfig(
    String suite,
    Integer version,
    Integer maxRounds,
    Integer maxTools,
    Integer maxSeconds,
    String requestedBy) {
  public EvaluationConfig {
    if (maxRounds != null && (maxRounds < 1 || maxRounds > 20))
      throw new IllegalArgumentException("评测模型轮次上限须在 1 至 20 之间。");
    if (maxTools != null && (maxTools < 0 || maxTools > 20))
      throw new IllegalArgumentException("评测工具上限须在 0 至 20 之间。");
    if (maxSeconds != null && (maxSeconds < 1 || maxSeconds > 300))
      throw new IllegalArgumentException("评测时间上限须在 1 至 300 秒之间。");
    if (requestedBy != null && requestedBy.length() > 80)
      throw new IllegalArgumentException("操作者标识不能超过 80 字。");
  }
}
