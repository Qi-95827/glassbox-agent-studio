package dev.workbench.control;

import dev.workbench.framework.context.CompactionRequest;
import dev.workbench.framework.context.CompactionSummarizer;
import dev.workbench.framework.context.ContextMessage;
import dev.workbench.memory.HybridRanker;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * L2 锚点摘要：保留固定 System 锚点与最近成对轮次，把中间历史压缩为可增量合并的四段结构化检查点。
 *
 * <p>摘要不可用时降级为本地关键句与实体抽取，绝不丢失上一轮已确认事实。
 */
final class ContextCheckpointBuilder {
  static final String CHECKPOINT_MARK = "【上下文压缩检查点】";
  private static final int L2_MAX_CHARS = 2_400;

  private final List<CompactionSummarizer> summarizers;

  ContextCheckpointBuilder(List<CompactionSummarizer> summarizers) {
    this.summarizers = summarizers == null ? List.of() : List.copyOf(summarizers);
  }

  /** 把中间历史替换为一个结构化检查点；没有可压缩区间时原样返回。 */
  Result build(List<ContextMessage> messages, CompactionRequest request, String focus) {
    int pinnedEnd = ContextWindowBoundaries.pinnedEnd(messages);
    int recencyStart =
        Math.max(pinnedEnd, ContextWindowBoundaries.roundStart(messages, request.recencyTurns()));
    if (recencyStart <= pinnedEnd) return Result.unchanged(messages);
    // 切分点必须落在工具调用原子组的边界：绝不让 assistant(tool_use) 与其 TOOL 结果分处两侧。
    recencyStart =
        Math.max(pinnedEnd, ContextWindowBoundaries.alignCut(messages, recencyStart));

    int previousIndex = previousCheckpointIndex(messages, pinnedEnd, recencyStart);
    int middleStart =
        Math.max(
            pinnedEnd,
            ContextWindowBoundaries.alignCut(
                messages, previousIndex < 0 ? pinnedEnd : previousIndex + 1));
    recencyStart = Math.max(middleStart, recencyStart);
    var middle = messages.subList(middleStart, recencyStart);
    String previous = previousIndex < 0 ? "" : messages.get(previousIndex).content();
    // 同一运行上一轮的结构化检查点参与增量合并，避免每轮都从零重写。
    String inherited = request.previousCheckpoint();
    if (inherited != null && !inherited.isBlank())
      previous = previous.isBlank() ? inherited : inherited + "\n\n" + previous;
    if (middle.isEmpty()) return Result.unchanged(messages);

    String summary = summarize(request, focus, previous, middle);
    if (summary.isBlank()) summary = fallbackCheckpoint(focus, previous, middle);
    if (summary.isBlank()) return Result.unchanged(messages);

    // 检查点只能出现在首条 USER 之前：严格角色序列模型不接受中途插入 SYSTEM。
    // 旧检查点必须一并替换掉（无论它已经在头部还是仍在中间），否则同一轮会出现两个检查点。
    int prefixEnd = previousIndex < 0 ? middleStart : Math.max(previousIndex, pinnedEnd);
    var head = new ArrayList<ContextMessage>(prefixEnd);
    for (int index = 0; index < prefixEnd; index++) {
      if (index == previousIndex) continue;
      head.add(messages.get(index));
    }
    int insertAt = Math.min(ContextWindowBoundaries.leadingSystemEnd(messages), head.size());
    head.add(insertAt, new ContextMessage("SYSTEM", null, summary, null));
    var updated = new ArrayList<ContextMessage>(head);
    updated.addAll(messages.subList(recencyStart, messages.size()));
    return new Result(updated, summary, summary, true);
  }

  private static int previousCheckpointIndex(List<ContextMessage> messages, int from, int to) {
    // 从列表头部开始找：上一轮可能已把检查点提升到首条 USER 之前，只从 pinnedEnd 起找会漏掉它，
    // 从而在同一轮里叠加出第二个检查点。只有带检查点标记的 SYSTEM 才会命中。
    for (int index = to - 1; index >= 0; index--) {
      if (index < from && index >= ContextWindowBoundaries.leadingSystemEnd(messages)) continue;
      var message = messages.get(index);
      if ("SYSTEM".equals(message.role())
          && message.content() != null
          && message.content().contains(CHECKPOINT_MARK)) return index;
    }
    return -1;
  }

  private String summarize(
      CompactionRequest request, String focus, String previous, List<ContextMessage> middle) {
    var candidate =
        request.summarizer() != null && request.summarizer().isAvailable()
            ? request.summarizer()
            : firstAvailable();
    if (candidate == null) return "";
    try {
      String text = candidate.summarize(request.scope(), focus, previous, middle);
      if (text == null || text.isBlank()) return "";
      return normalizeCheckpoint(text);
    } catch (RuntimeException e) {
      return "";
    }
  }

  private CompactionSummarizer firstAvailable() {
    for (var summarizer : summarizers)
      if (summarizer != null && summarizer.isAvailable()) return summarizer;
    return null;
  }

  private static String normalizeCheckpoint(String text) {
    String stripped = text.strip();
    if (stripped.contains(CHECKPOINT_MARK)) return stripped;
    return (CHECKPOINT_MARK + "\n" + stripped).strip();
  }

  private static String fallbackCheckpoint(
      String focus, String previous, List<ContextMessage> middle) {
    var sections = mergedSections(previous);
    var facts = new ArrayList<String>();
    var constraints = new ArrayList<String>();
    var states = new ArrayList<String>();
    var questions = new ArrayList<String>();
    for (var message : middle) {
      if (message.content() == null) continue;
      String role = message.role();
      for (String raw : message.content().lines().toList()) {
        String line = raw.strip();
        if (line.isBlank() || line.length() < 4) continue;
        String lower = line.toLowerCase(Locale.ROOT);
        if (line.endsWith("?")
            || line.endsWith("？")
            || line.contains("待办")
            || lower.contains("todo")
            || lower.contains("next step")
            || line.contains("未解决")
            || line.contains("下一步")) {
          addLimited(questions, line);
        } else if (line.contains("禁止")
            || line.contains("不得")
            || line.contains("必须")
            || line.contains("仅允许")
            || line.contains("只能")
            || lower.contains("must")
            || lower.contains("never")
            || lower.contains("constraint")) {
          addLimited(constraints, line);
        } else if (ContextTextSignals.hasPath(line)
            || line.contains("修改")
            || line.contains("写入")
            || line.contains("创建")
            || lower.contains("changed")
            || lower.contains("written")
            || "TOOL".equals(role)) {
          addLimited(states, line);
        } else if (ContextTextSignals.hasNumber(line) || line.length() >= 12) {
          addLimited(facts, line);
        }
      }
    }
    var mergedFacts = merge(sections.get("confirmed_facts"), rankByFocus(facts, focus), 8);
    var mergedConstraints = merge(sections.get("active_constraints"), constraints, 8);
    var mergedStates = merge(sections.get("file_and_tool_state"), states, 8);
    var mergedQuestions = merge(sections.get("open_questions"), questions, 8);
    var text = new StringBuilder(CHECKPOINT_MARK).append('\n');
    appendSection(text, "## confirmed_facts（已确认事实）", mergedFacts);
    appendSection(text, "## active_constraints（生效约束）", mergedConstraints);
    appendSection(text, "## file_and_tool_state（文件与工具状态）", mergedStates);
    appendSection(text, "## open_questions（未解决疑问）", mergedQuestions);
    if (text.length() > L2_MAX_CHARS) {
      text.setLength(L2_MAX_CHARS - 1);
      text.append('…');
    }
    return text.toString();
  }

  /** 解析上一轮检查点，保留事实不发生退化。 */
  private static Map<String, List<String>> mergedSections(String previous) {
    var result = new HashMap<String, List<String>>();
    if (previous == null || previous.isBlank()) return result;
    String section = "";
    for (String raw : previous.lines().toList()) {
      String line = raw.strip();
      if (line.startsWith("## ")) {
        section = sectionKey(line);
        result.putIfAbsent(section, new ArrayList<>());
        continue;
      }
      if (line.startsWith("- ") && !section.isBlank()) {
        result.computeIfAbsent(section, ignored -> new ArrayList<>()).add(line.substring(2));
      }
    }
    return result;
  }

  private static String sectionKey(String heading) {
    if (heading.contains("confirmed_facts")) return "confirmed_facts";
    if (heading.contains("active_constraints")) return "active_constraints";
    if (heading.contains("file_and_tool_state")) return "file_and_tool_state";
    if (heading.contains("open_questions")) return "open_questions";
    return heading;
  }

  private static List<String> rankByFocus(List<String> lines, String focus) {
    if (focus == null || focus.isBlank() || lines.size() < 2) return lines;
    var inputs = new ArrayList<HybridRanker.Input>();
    for (int index = 0; index < lines.size(); index++)
      inputs.add(new HybridRanker.Input("l" + index, lines.get(index), 0));
    var ranked = HybridRanker.rank(inputs, focus);
    var order = new ArrayList<Integer>();
    for (int index = 0; index < lines.size(); index++) order.add(index);
    order.sort(
        Comparator.comparingDouble(
                (Integer index) ->
                    ranked.containsKey("l" + index) ? ranked.get("l" + index).bm25() : 0)
            .reversed());
    return order.stream().map(lines::get).toList();
  }

  private static List<String> merge(List<String> previous, List<String> next, int limit) {
    var result = new LinkedHashSet<String>();
    if (previous != null) {
      for (String line : previous) if (!line.isBlank()) result.add(line.strip());
    }
    for (String line : next) if (!line.isBlank()) result.add(line.strip());
    return result.stream().limit(limit).toList();
  }

  private static void appendSection(StringBuilder text, String title, List<String> lines) {
    if (lines.isEmpty()) return;
    text.append(title).append('\n');
    for (String line : lines)
      text.append("- ").append(ContextTextSignals.compact(line, 210)).append('\n');
  }

  private static void addLimited(List<String> lines, String line) {
    if (lines.size() >= 10) return;
    lines.add(ContextTextSignals.compact(line, 210));
  }

  /** 一次 L2 摘要结果：替换后的消息、预览正文与结构化锚点。 */
  record Result(List<ContextMessage> messages, String preview, String anchor, boolean changed) {
    static Result unchanged(List<ContextMessage> messages) {
      return new Result(messages, "", "", false);
    }
  }
}
