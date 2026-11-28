package dev.workbench.control;

import dev.workbench.framework.context.CompactionRequest;
import dev.workbench.framework.context.ContextMessage;
import dev.workbench.memory.HybridRanker;
import java.util.ArrayList;
import java.util.List;

/**
 * L3 硬保护：仍超限时按相关性从低到高淘汰正文，绝不删除 System 规则与结构化锚点，
 * 也绝不拆散 {@code tool_call}/{@code tool_result} 成对关系。
 */
final class ContextHardGuard {
  static final String HARD_GUARD_MARK = "【上下文硬保护】";

  /** 把中间历史裁剪到安全水位以下；没有任何改动时原样返回。 */
  Result guard(List<ContextMessage> messages, CompactionRequest request, String focus) {
    var working = new ArrayList<>(messages);
    int pinnedEnd = ContextWindowBoundaries.pinnedEnd(working);
    int recencyStart = Math.max(pinnedEnd, ContextWindowBoundaries.roundStart(working, 1));
    recencyStart = Math.max(pinnedEnd, ContextWindowBoundaries.alignCut(working, recencyStart));
    if (recencyStart <= pinnedEnd) {
      recencyStart = working.size();
    }
    boolean changed = false;
    int safeLimit = Math.max(1, (int) (request.contextLimit() * 0.90));
    // 已处理过的消息不再重复挑选：旧实现在单条消息上反复叠加前缀，体积不减时空转 200 次。
    var processed =
        java.util.Collections.newSetFromMap(
            new java.util.IdentityHashMap<ContextMessage, Boolean>());
    for (int guard = 0; guard < 200 && ContextSegmentEstimator.estimate(working) > safeLimit; guard++) {
      int candidate = lowestValueIndex(working, pinnedEnd, recencyStart, focus, processed);
      if (candidate < 0) break;
      var message = working.get(candidate);
      int before = ContextSegmentEstimator.estimate(working);
      if (message.toolResult()) {
        // 工具结果只缩短不删除：删掉它会让对应的 assistant(tool_use) 变成孤儿并触发 400。
        int remaining = Math.max(400, (safeLimit - before) * 4);
        var excerpt =
            ContextExcerptSelector.select(message.content(), focus, Math.max(400, remaining));
        String content =
            "[HardGuard excerpt: chars="
                + message.content().length()
                + ", 已保留最相关 "
                + excerpt.windows().size()
                + " 段]\n"
                + ContextExcerptSelector.renderExcerptBody(excerpt);
        var shrunk =
            new ContextMessage(message.role(), message.name(), content, message.toolCallId());
        working.set(candidate, shrunk);
        if (ContextSegmentEstimator.estimate(working) >= before) {
          // 已经足够短：还原并标记，继续处理下一条，绝不在同一条上反复叠加。
          working.set(candidate, message);
          processed.add(message);
          continue;
        }
        processed.add(shrunk);
      } else {
        if (message.toolCall()) {
          // assistant(tool_use) 不能单删：它的结果消息会变成孤儿。
          processed.add(message);
          continue;
        }
        working.remove(candidate);
        if (recencyStart > candidate) recencyStart--;
      }
      changed = true;
    }
    if (!changed) return Result.unchanged(messages);
    ensureHardGuardNotice(working);
    return new Result(
        working,
        HARD_GUARD_MARK + "已按相关性从低到高淘汰旧正文；成对工具关系与结构化锚点保持不变。",
        true);
  }

  private static int lowestValueIndex(
      List<ContextMessage> messages,
      int pinnedEnd,
      int recencyStart,
      String focus,
      java.util.Set<ContextMessage> processed) {
    int candidate = -1;
    double lowest = Double.MAX_VALUE;
    var inputs = new ArrayList<HybridRanker.Input>();
    var offsets = new ArrayList<Integer>();
    for (int index = pinnedEnd; index < Math.min(recencyStart, messages.size()); index++) {
      var message = messages.get(index);
      if ("SYSTEM".equals(message.role())) continue;
      if (processed.contains(message)) continue;
      if (message.content() == null || message.content().isBlank()) {
        offsets.add(index);
        inputs.add(new HybridRanker.Input("m" + index, "", 0));
        continue;
      }
      offsets.add(index);
      inputs.add(new HybridRanker.Input("m" + index, message.content(), 0));
    }
    if (inputs.isEmpty()) return -1;
    var ranked = HybridRanker.rank(inputs, focus);
    for (int position = 0; position < inputs.size(); position++) {
      int index = offsets.get(position);
      var message = messages.get(index);
      double score = ranked.containsKey("m" + index) ? ranked.get("m" + index).bm25() : 0;
      score += ContextTextSignals.structuralBoost(message.content()) * 0.35;
      // 空正文优先淘汰；工具结果只缩短不删除。
      if (message.content() == null || message.content().isBlank()) score = -1;
      if (score < lowest) {
        lowest = score;
        candidate = index;
      }
    }
    return candidate;
  }

  private static void ensureHardGuardNotice(List<ContextMessage> messages) {
    for (int index = 0; index < messages.size(); index++) {
      var message = messages.get(index);
      if ("SYSTEM".equals(message.role())
          && message.content() != null
          && message.content().contains(HARD_GUARD_MARK)) return;
    }
    int at = Math.min(ContextWindowBoundaries.leadingSystemEnd(messages), messages.size());
    messages.add(
        at,
        new ContextMessage(
            "SYSTEM", null, HARD_GUARD_MARK + "中间历史已按相关性裁剪；任务锚点、结构化检查点与最近一轮完整消息保持不变。", null));
  }

  /** 一次硬保护结果：裁剪后的消息、通知正文与是否发生变化。 */
  record Result(List<ContextMessage> messages, String preview, boolean changed) {
    static Result unchanged(List<ContextMessage> messages) {
      return new Result(messages, "", false);
    }
  }
}
