package dev.workbench.control;

import dev.workbench.framework.context.ContextMessage;
import java.util.List;

/** 上下文消息窗口的边界计算：固定锚点、最近轮次与工具调用原子组对齐。 */
final class ContextWindowBoundaries {
  private ContextWindowBoundaries() {}

  /** 从后向前数第 {@code keepRounds} 个 ASSISTANT 的下标；轮次不足时从头开始。 */
  static int roundStart(List<ContextMessage> messages, int keepRounds) {
    int seen = 0;
    for (int index = messages.size() - 1; index >= 0; index--) {
      if ("ASSISTANT".equals(messages.get(index).role())) {
        seen++;
        if (seen == keepRounds) return index;
      }
    }
    return 0;
  }

  /** 首段连续 SYSTEM 加紧随的第一条 USER 的结束位置，即固定锚点之后的下标。 */
  static int pinnedEnd(List<ContextMessage> messages) {
    int index = 0;
    while (index < messages.size() && "SYSTEM".equals(messages.get(index).role())) index++;
    if (index < messages.size() && "USER".equals(messages.get(index).role())) index++;
    return Math.max(1, index);
  }

  /** 首段连续 SYSTEM 的结束位置；检查点与硬保护通知只能插在这里，保证首条 USER 之前没有新 SYSTEM。 */
  static int leadingSystemEnd(List<ContextMessage> messages) {
    int index = 0;
    while (index < messages.size() && "SYSTEM".equals(messages.get(index).role())) index++;
    return index;
  }

  /**
   * 把切分点左移到工具调用原子组的边界。
   *
   * <p>若切分点上正好是 TOOL 结果，说明它的 assistant(tool_use) 会被留在另一侧；左移直到该位置不再是 TOOL。
   * 这样任何一侧都只会包含完整的「调用 + 结果」组。
   */
  static int alignCut(List<ContextMessage> messages, int cut) {
    int index = Math.max(0, Math.min(cut, messages.size()));
    while (index > 0 && index < messages.size() && messages.get(index).toolResult()) index--;
    return index;
  }
}
