package dev.workbench.control;

import dev.workbench.framework.context.CompactionRequest;
import dev.workbench.framework.context.CompactionResult;
import dev.workbench.framework.context.CompactionTier;
import dev.workbench.framework.context.ContextBreakdown;
import dev.workbench.framework.context.ContextCompactionPolicy;
import dev.workbench.framework.context.ContextMessage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 工业级四级分层上下文压缩。
 *
 * <p>L0 只做精确分桶；L1 卸载较早的大体积工具结果；L2 保留固定锚点与最近成对轮次，把中间历史
 * 压缩成结构化检查点；L3 在仍超限时按优先级裁剪低价值正文。任何等级都不会拆分 tool_call 与
 * tool_result 的成对关系。
 */
@Component
public class MultiTierContextCompactor implements ContextCompactionPolicy {
  private static final int L1_MIN_CHARS = 1_500;
  private static final int L1_HEAD = 200;
  private static final int L1_TAIL = 100;
  private static final int SUMMARY_MAX_CHARS = 1_200;
  private static final Pattern PATH_PATTERN =
      Pattern.compile("([A-Za-z0-9_./-]+\\.(?:java|js|jsx|ts|tsx|py|html|css|md|json|sql|yml|yaml))");

  @Override
  public CompactionResult evaluateAndCompact(CompactionRequest request) {
    List<ContextMessage> working = new ArrayList<>(request.messages());
    int before = estimate(working);
    int limit = request.contextLimit();
    var tier = CompactionTier.L0_OBSERVE_ONLY;
    var offloaded = new ArrayList<String>();
    String summaryPreview = "";

    if (ratio(before, limit) >= request.l1Threshold()) {
      var result = offloadToolOutputs(working, request.offloadAfterTurns());
      if (result.changed()) {
        working = result.messages();
        offloaded.addAll(result.ids());
        tier = CompactionTier.L1_TOOL_OFFLOAD;
      }
    }
    int afterL1 = estimate(working);
    if (ratio(afterL1, limit) >= request.l2Threshold()) {
      var result = slideAndSummarize(working, request.recencyTurns());
      if (result.changed()) {
        working = result.messages();
        summaryPreview = result.preview();
        tier = CompactionTier.L2_SLIDING_SUMMARY;
      }
    }
    int afterL2 = estimate(working);
    if (ratio(afterL2, limit) >= request.l3Threshold()) {
      var result = hardGuard(working);
      if (result.changed()) {
        working = result.messages();
        if (summaryPreview.isBlank()) summaryPreview = result.preview();
        tier = CompactionTier.L3_HARD_GUARD;
      }
    }

    int total = estimate(working);
    var breakdown =
        new ContextBreakdown(
            limit,
            before,
            total,
            segments(working),
            tier,
            Math.max(0, before - total),
            before < 1 ? 0 : (int) Math.round((before - total) * 100.0 / before),
            List.copyOf(offloaded),
            summaryPreview);
    return new CompactionResult(working, breakdown);
  }

  private static double ratio(int tokens, int limit) {
    return limit < 1 ? 0 : (double) tokens / limit;
  }

  /** 按字符数/4 保守估算 Token；中文与英文混排时仍以字符规模为主。 */
  static int estimate(List<ContextMessage> messages) {
    long total = 0;
    for (var message : messages) {
      int chars = message.content() == null ? 0 : message.content().length();
      total += Math.max(1, (chars + 3L) / 4L);
    }
    return (int) Math.min(Integer.MAX_VALUE, Math.max(0, total));
  }

  private static Map<String, Integer> segments(List<ContextMessage> messages) {
    var result = new LinkedHashMap<String, Integer>();
    for (var message : messages) {
      String segment = segmentOf(message);
      result.merge(segment, estimate(List.of(message)), Integer::sum);
    }
    return result;
  }

  private static String segmentOf(ContextMessage message) {
    String content = message.content() == null ? "" : message.content();
    if ("TOOL".equals(message.role())) return "TOOL_OUTPUTS";
    if ("SYSTEM".equals(message.role())) {
      if (content.contains("内置技能") || content.contains("Built-in skill")) return "SKILLS";
      if (content.contains("项目背景") || content.contains("project context")) return "MEMORY";
      if (content.contains("交接") || content.contains("handoff")) return "HANDOFF";
      return "SYSTEM";
    }
    if (content.contains("项目背景") || content.contains("project context")) return "MEMORY";
    if (content.contains("交接") || content.contains("handoff")) return "HANDOFF";
    return "HISTORY";
  }

  private static int roundStart(List<ContextMessage> messages, int keepRounds) {
    int seen = 0;
    for (int index = messages.size() - 1; index >= 0; index--) {
      if ("ASSISTANT".equals(messages.get(index).role())) {
        seen++;
        if (seen == keepRounds) return index;
      }
    }
    return 0;
  }

  private static OffloadResult offloadToolOutputs(List<ContextMessage> messages, int keepTurns) {
    int keepFrom = roundStart(messages, keepTurns);
    var updated = new ArrayList<ContextMessage>(messages.size());
    var ids = new ArrayList<String>();
    boolean changed = false;
    for (int index = 0; index < messages.size(); index++) {
      var message = messages.get(index);
      if (index < keepFrom
          && message.toolResult()
          && message.content() != null
          && message.content().length() > L1_MIN_CHARS) {
        String id = message.toolCallId() == null ? "tool-result-" + index : message.toolCallId();
        updated.add(new ContextMessage(message.role(), message.name(), offloaded(message, id), id));
        ids.add(id);
        changed = true;
        continue;
      }
      updated.add(message);
    }
    return new OffloadResult(updated, ids, changed);
  }

  private static String offloaded(ContextMessage message, String id) {
    String content = message.content();
    String head = content.substring(0, Math.min(L1_HEAD, content.length()));
    String tail =
        content.length() <= L1_TAIL ? "" : content.substring(content.length() - L1_TAIL);
    String path = detectPath(content);
    int lines = (int) content.lines().count();
    return head
        + "\n[Offloaded: toolCallId="
        + id
        + (path == null ? "" : ", path=" + path)
        + ", chars="
        + content.length()
        + ", lines="
        + lines
        + ", use workspace_read if needed]"
        + (tail.isBlank() ? "" : "\n" + tail);
  }

  private static String detectPath(String content) {
    Matcher matcher = PATH_PATTERN.matcher(content);
    return matcher.find() ? matcher.group(1) : null;
  }

  private static SummaryResult slideAndSummarize(List<ContextMessage> messages, int recencyTurns) {
    int pinnedEnd = pinnedEnd(messages);
    int recencyStart = Math.max(pinnedEnd, roundStart(messages, recencyTurns));
    if (recencyStart <= pinnedEnd) return SummaryResult.unchanged(messages);
    var middle = messages.subList(pinnedEnd, recencyStart);
    if (middle.isEmpty()) return SummaryResult.unchanged(messages);
    String preview = checkpointSummary(middle);
    var updated = new ArrayList<ContextMessage>(pinnedEnd + 1 + (messages.size() - recencyStart));
    updated.addAll(messages.subList(0, pinnedEnd));
    updated.add(new ContextMessage("SYSTEM", null, preview, null));
    updated.addAll(messages.subList(recencyStart, messages.size()));
    return new SummaryResult(updated, preview, true);
  }

  /** 固定锚点：开头连续 System 消息 + 第一条 User 消息（任务目标）。 */
  private static int pinnedEnd(List<ContextMessage> messages) {
    int index = 0;
    while (index < messages.size() && "SYSTEM".equals(messages.get(index).role())) index++;
    if (index < messages.size() && "USER".equals(messages.get(index).role())) index++;
    return Math.max(1, index);
  }

  private static String checkpointSummary(List<ContextMessage> middle) {
    var facts = new ArrayList<String>();
    var files = new ArrayList<String>();
    var assumptions = new ArrayList<String>();
    var todos = new ArrayList<String>();
    for (var message : middle) {
      for (String line : message.content().lines().map(String::strip).toList()) {
        if (line.isBlank()) continue;
        String lower = line.toLowerCase(java.util.Locale.ROOT);
        if (line.contains("待办") || line.contains("TODO") || line.contains("下一步"))
          addLimited(todos, line);
        else if (line.contains("排除") || line.contains("假设") || lower.contains("assumption"))
          addLimited(assumptions, line);
        else if (line.contains("写入") || line.contains("修改") || lower.contains("changed"))
          addLimited(files, line);
        else addLimited(facts, line);
      }
    }
    StringBuilder text = new StringBuilder("【上下文压缩检查点】\n");
    appendSection(text, "已确认事实", facts);
    appendSection(text, "已修改文件与回执", files);
    appendSection(text, "排除的假设", assumptions);
    appendSection(text, "当前待办", todos);
    if (text.length() > SUMMARY_MAX_CHARS) {
      text.setLength(SUMMARY_MAX_CHARS - 1);
      text.append('…');
    }
    return text.toString();
  }

  private static void appendSection(StringBuilder text, String title, List<String> lines) {
    if (lines.isEmpty()) return;
    text.append(title).append("：\n");
    for (String line : lines) text.append("- ").append(line).append('\n');
  }

  private static void addLimited(List<String> lines, String line) {
    if (lines.size() >= 6) return;
    lines.add(line.length() <= 180 ? line : line.substring(0, 180) + "…");
  }

  private static SummaryResult hardGuard(List<ContextMessage> messages) {
    int pinnedEnd = pinnedEnd(messages);
    int lastRound = roundStart(messages, 1);
    int recencyStart = Math.max(pinnedEnd, lastRound);
    if (recencyStart <= pinnedEnd) {
      // 没有可裁剪的中间段时，仅压缩最长的非锚点正文，保留最近一轮完整消息。
      var trimmed = new ArrayList<>(messages);
      while (estimate(trimmed) > 1 && trimmed.size() > pinnedEnd + 1) {
        int drop = longestAfter(trimmed, pinnedEnd);
        if (drop < 0) break;
        trimmed.remove(drop);
      }
      return new SummaryResult(trimmed, "【上下文硬保护】已裁剪低优先级旧消息。", trimmed.size() != messages.size());
    }
    var updated = new ArrayList<ContextMessage>(pinnedEnd + 1 + (messages.size() - recencyStart));
    updated.addAll(messages.subList(0, pinnedEnd));
    updated.add(
        new ContextMessage(
            "SYSTEM",
            null,
            "【上下文硬保护】中间历史已裁剪；仅保留任务锚点、结构化摘要与最近一轮完整消息。",
            null));
    updated.addAll(messages.subList(recencyStart, messages.size()));
    return new SummaryResult(updated, "【上下文硬保护】已裁剪低优先级旧消息。", true);
  }

  private static int longestAfter(List<ContextMessage> messages, int start) {
    int candidate = -1;
    int length = -1;
    for (int index = start; index < messages.size(); index++) {
      int current = messages.get(index).content().length();
      if (current > length) {
        length = current;
        candidate = index;
      }
    }
    return candidate;
  }

  private record OffloadResult(List<ContextMessage> messages, List<String> ids, boolean changed) {}

  private record SummaryResult(List<ContextMessage> messages, String preview, boolean changed) {
    static SummaryResult unchanged(List<ContextMessage> messages) {
      return new SummaryResult(messages, "", false);
    }
  }
}
