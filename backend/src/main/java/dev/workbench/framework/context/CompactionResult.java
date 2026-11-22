package dev.workbench.framework.context;

import java.util.List;

/** 压缩后的消息与玻璃盒预算快照。 */
public record CompactionResult(List<ContextMessage> messages, ContextBreakdown breakdown) {
  public CompactionResult {
    messages = messages == null ? List.of() : List.copyOf(messages);
    if (breakdown == null) throw new IllegalArgumentException("压缩结果缺少上下文预算快照。");
  }
}
