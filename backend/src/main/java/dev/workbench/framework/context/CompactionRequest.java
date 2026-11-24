package dev.workbench.framework.context;

import java.util.List;

/**
 * 一次上下文压缩请求。
 *
 * @param messages 当前完整消息列表，顺序与模型请求一致
 * @param contextLimit 模型上下文窗口上限（Token）
 * @param recencyTurns L2 必须完整保留的最近轮数，默认 4
 * @param offloadAfterTurns L1 只卸载早于最近若干轮的工具结果，默认 2
 * @param l1Threshold L1 触发水位，默认 0.60
 * @param l2Threshold L2 触发水位，默认 0.78
 * @param l3Threshold L3 触发水位，默认 0.92
 * @param focusQuery 当前任务目标/问题，用于 Query-Aware 摘录；为空时自动取最后一条 USER 消息
 * @param summarizer 可选 L2 结构化摘要实现；为空时降级为本地关键句与实体抽取
 * @param scope 运行归属、BYOK 连接与用量回调；后台线程没有会话，必须由运行上下文显式透传
 */
public record CompactionRequest(
    List<ContextMessage> messages,
    int contextLimit,
    int recencyTurns,
    int offloadAfterTurns,
    double l1Threshold,
    double l2Threshold,
    double l3Threshold,
    String focusQuery,
    CompactionSummarizer summarizer,
    CompactionScope scope) {
  public CompactionRequest {
    messages = messages == null ? List.of() : List.copyOf(messages);
    if (contextLimit < 1) throw new IllegalArgumentException("上下文窗口上限必须为正数。");
    if (recencyTurns < 1 || offloadAfterTurns < 1) throw new IllegalArgumentException("保留轮数必须为正数。");
    if (!(l1Threshold > 0
        && l1Threshold < l2Threshold
        && l2Threshold < l3Threshold
        && l3Threshold < 1)) throw new IllegalArgumentException("压缩水位必须满足 0 < L1 < L2 < L3 < 1。");
    focusQuery = focusQuery == null ? "" : focusQuery.strip();
    scope = scope == null ? CompactionScope.NONE : scope;
  }

  /** 兼容既有七参调用；目标问题由 {@link #of} 自动推导。 */
  public CompactionRequest(
      List<ContextMessage> messages,
      int contextLimit,
      int recencyTurns,
      int offloadAfterTurns,
      double l1Threshold,
      double l2Threshold,
      double l3Threshold) {
    this(
        messages,
        contextLimit,
        recencyTurns,
        offloadAfterTurns,
        l1Threshold,
        l2Threshold,
        l3Threshold,
        lastUserMessage(messages),
        null,
        CompactionScope.NONE);
  }

  public CompactionRequest(
      List<ContextMessage> messages,
      int contextLimit,
      int recencyTurns,
      int offloadAfterTurns,
      double l1Threshold,
      double l2Threshold,
      double l3Threshold,
      String focusQuery) {
    this(
        messages,
        contextLimit,
        recencyTurns,
        offloadAfterTurns,
        l1Threshold,
        l2Threshold,
        l3Threshold,
        focusQuery,
        null,
        CompactionScope.NONE);
  }

  public CompactionRequest(
      List<ContextMessage> messages,
      int contextLimit,
      int recencyTurns,
      int offloadAfterTurns,
      double l1Threshold,
      double l2Threshold,
      double l3Threshold,
      String focusQuery,
      CompactionSummarizer summarizer) {
    this(
        messages,
        contextLimit,
        recencyTurns,
        offloadAfterTurns,
        l1Threshold,
        l2Threshold,
        l3Threshold,
        focusQuery,
        summarizer,
        CompactionScope.NONE);
  }

  public static CompactionRequest of(List<ContextMessage> messages, int contextLimit) {
    return new CompactionRequest(
        messages,
        contextLimit,
        4,
        2,
        0.60,
        0.78,
        0.92,
        lastUserMessage(messages),
        null,
        CompactionScope.NONE);
  }

  /** 生产装配使用：同时携带运行归属，避免后台线程回退到 LOCAL 默认连接。 */
  public static CompactionRequest of(
      List<ContextMessage> messages, int contextLimit, CompactionScope scope) {
    return new CompactionRequest(
        messages, contextLimit, 4, 2, 0.60, 0.78, 0.92, lastUserMessage(messages), null, scope);
  }

  /** 生产装配可注入真实摘要器，同时保留意图聚焦。 */
  public CompactionRequest withSummarizer(CompactionSummarizer value) {
    return new CompactionRequest(
        messages,
        contextLimit,
        recencyTurns,
        offloadAfterTurns,
        l1Threshold,
        l2Threshold,
        l3Threshold,
        focusQuery,
        value,
        scope);
  }

  /** 绑定运行归属；未显式绑定时保持空上下文。 */
  public CompactionRequest withScope(CompactionScope value) {
    return new CompactionRequest(
        messages,
        contextLimit,
        recencyTurns,
        offloadAfterTurns,
        l1Threshold,
        l2Threshold,
        l3Threshold,
        focusQuery,
        summarizer,
        value);
  }

  private static String lastUserMessage(List<ContextMessage> messages) {
    if (messages == null) return "";
    for (int index = messages.size() - 1; index >= 0; index--) {
      var message = messages.get(index);
      if ("USER".equals(message.role())
          && message.content() != null
          && !message.content().isBlank()) return message.content().strip();
    }
    return "";
  }
}
