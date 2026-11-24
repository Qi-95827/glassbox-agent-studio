package dev.workbench.framework.context;

/**
 * 后台辅助模型调用的用量回调。
 *
 * <p>L2 摘要这类调用不经过 Agent 主循环，不能复用主调用的“开始/结算”配对，必须由运行上下文显式 提供一个只记录用量的出口，才能把 Token 与费用计入当前运行的台账。
 */
@FunctionalInterface
public interface CompactionUsageSink {
  /** 未接入运行台账时的空实现；调用方不应因此跳过真正的计费链路。 */
  CompactionUsageSink NONE = (inputTokens, outputTokens) -> {};

  void record(Integer inputTokens, Integer outputTokens);
}
