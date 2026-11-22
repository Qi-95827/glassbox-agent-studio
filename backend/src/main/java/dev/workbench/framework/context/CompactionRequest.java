package dev.workbench.framework.context;

import java.util.List;

/**
 * 压缩请求。
 *
 * @param messages 当前完整消息列表，顺序与模型请求一致
 * @param contextLimit 模型上下文窗口上限（Token）
 * @param recencyTurns L2 必须完整保留的最近轮数，默认 4
 * @param offloadAfterTurns L1 只卸载早于最近若干轮的工具结果，默认 2
 * @param l1Threshold L1 触发水位，默认 0.60
 * @param l2Threshold L2 触发水位，默认 0.78
 * @param l3Threshold L3 触发水位，默认 0.92
 */
public record CompactionRequest(
    List<ContextMessage> messages,
    int contextLimit,
    int recencyTurns,
    int offloadAfterTurns,
    double l1Threshold,
    double l2Threshold,
    double l3Threshold) {
  public CompactionRequest {
    messages = messages == null ? List.of() : List.copyOf(messages);
    if (contextLimit < 1) throw new IllegalArgumentException("上下文窗口上限必须为正数。");
    if (recencyTurns < 1 || offloadAfterTurns < 1)
      throw new IllegalArgumentException("保留轮数必须为正数。");
    if (!(l1Threshold > 0 && l1Threshold < l2Threshold && l2Threshold < l3Threshold && l3Threshold < 1))
      throw new IllegalArgumentException("压缩水位必须满足 0 < L1 < L2 < L3 < 1。");
  }

  public static CompactionRequest of(List<ContextMessage> messages, int contextLimit) {
    return new CompactionRequest(messages, contextLimit, 4, 2, 0.60, 0.78, 0.92);
  }
}
