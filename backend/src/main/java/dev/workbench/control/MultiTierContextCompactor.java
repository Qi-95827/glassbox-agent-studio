package dev.workbench.control;

import dev.workbench.framework.context.CompactionRequest;
import dev.workbench.framework.context.CompactionResult;
import dev.workbench.framework.context.CompactionSummarizer;
import dev.workbench.framework.context.CompactionTier;
import dev.workbench.framework.context.ContextArtifactStore;
import dev.workbench.framework.context.ContextBreakdown;
import dev.workbench.framework.context.ContextCompactionPolicy;
import dev.workbench.framework.context.ContextMessage;
import dev.workbench.memory.HybridRanker;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 工业级四级上下文压缩（Query-Aware + Artifact 卸载 + 增量锚点摘要）。
 *
 * <p><b>L0</b> 只做精确分桶；<b>L1</b> 把超长工具输出完整落盘为 Artifact，并依据当前目标问题
 * 选出最相关段落留在消息体，彻底废除“死留头 200 + 尾 100 字”；<b>L2</b> 保留固定 System 锚点与最近
 * 成对轮次，把中间历史压缩为可增量合并的四段结构化检查点；<b>L3</b> 仅在仍超限时按相关性从低到高
 * 淘汰正文，绝不删除 System 规则、结构化锚点，也绝不拆散 {@code tool_call}/{@code tool_result} 成对关系。
 */
@Component
public class MultiTierContextCompactor implements ContextCompactionPolicy {
  private static final int L1_MIN_CHARS = 1_500;
  private static final int L1_WINDOW_CHARS = 520;
  private static final int L1_PREVIEW_CHARS = 1_200;
  private static final int L2_MAX_CHARS = 2_400;
  private static final int OUTLINE_LIMIT = 18;
  private static final String CHECKPOINT_MARK = "【上下文压缩检查点】";
  private static final String HARD_GUARD_MARK = "【上下文硬保护】";
  private static final Pattern PATH_PATTERN =
      Pattern.compile(
          "([A-Za-z0-9_./-]+\\.(?:java|js|jsx|ts|tsx|py|html|css|md|json|sql|yml|yaml|xml|sh))");
  private static final Pattern EXCEPTION_PATTERN =
      Pattern.compile("(?i)(exception|error|failed|failure|caused by|报错|异常|失败|超时|traceback)");
  private static final Pattern NUMBER_PATTERN = Pattern.compile("\\d{2,}");

  private final List<ContextArtifactStore> artifactStores;
  private final List<CompactionSummarizer> summarizers;

  public MultiTierContextCompactor() {
    this(List.of(), List.of());
  }

  @Autowired
  public MultiTierContextCompactor(
      List<ContextArtifactStore> artifactStores, List<CompactionSummarizer> summarizers) {
    this.artifactStores = artifactStores == null ? List.of() : List.copyOf(artifactStores);
    this.summarizers = summarizers == null ? List.of() : List.copyOf(summarizers);
  }

  @Override
  public CompactionResult evaluateAndCompact(CompactionRequest request) {
    List<ContextMessage> working = new ArrayList<>(request.messages());
    int before = estimate(working);
    int limit = request.contextLimit();
    String focus = focus(request, working);
    var tier = CompactionTier.L0_OBSERVE_ONLY;
    var offloaded = new ArrayList<String>();
    var artifactRefs = new ArrayList<String>();
    String summaryPreview = "";
    String structuredAnchors = "";
    String retainedPreview = "";

    if (ratio(before, limit) >= request.l1Threshold()) {
      var result = offloadToolOutputs(working, request.offloadAfterTurns(), focus);
      if (result.changed()) {
        working = result.messages();
        offloaded.addAll(result.ids());
        artifactRefs.addAll(result.refs());
        retainedPreview = result.preview();
        tier = CompactionTier.L1_TOOL_OFFLOAD;
      }
    }
    int afterL1 = estimate(working);
    if (ratio(afterL1, limit) >= request.l2Threshold()) {
      var result = anchoredSummarize(working, request, focus);
      if (result.changed()) {
        working = result.messages();
        summaryPreview = result.preview();
        structuredAnchors = result.anchor();
        tier = CompactionTier.L2_SLIDING_SUMMARY;
      }
    }
    int afterL2 = estimate(working);
    if (ratio(afterL2, limit) >= request.l3Threshold()) {
      var result = hardGuard(working, request, focus);
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
            summaryPreview,
            List.copyOf(artifactRefs),
            retainedPreview,
            structuredAnchors);
    return new CompactionResult(working, breakdown);
  }

  private static double ratio(int tokens, int limit) {
    return limit < 1 ? 0 : (double) tokens / limit;
  }

  /** 与评测 Runner 同口径的保守 Token 估算（字符/4）。 */
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
    if ("TOOL".equals(message.role()) || "TOOL_OBSERVATION".equals(message.role()))
      return "TOOL_OUTPUTS";
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

  private static String focus(CompactionRequest request, List<ContextMessage> messages) {
    if (request.focusQuery() != null && !request.focusQuery().isBlank())
      return request.focusQuery().strip();
    for (int index = messages.size() - 1; index >= 0; index--) {
      var message = messages.get(index);
      if ("USER".equals(message.role()) && message.content() != null && !message.content().isBlank())
        return message.content().strip();
    }
    return "";
  }

  // ---------------------------------------------------------------------------
  // L1：Artifact 落盘 + Query-Aware 高相关片段摘录
  // ---------------------------------------------------------------------------

  private OffloadResult offloadToolOutputs(
      List<ContextMessage> messages, int keepTurns, String focus) {
    int keepFrom = roundStart(messages, keepTurns);
    var updated = new ArrayList<ContextMessage>(messages.size());
    var ids = new ArrayList<String>();
    var refs = new ArrayList<String>();
    var preview = new StringBuilder();
    boolean changed = false;
    for (int index = 0; index < messages.size(); index++) {
      var message = messages.get(index);
      if (index < keepFrom
          && message.toolResult()
          && message.content() != null
          && message.content().length() > L1_MIN_CHARS) {
        String id = message.toolCallId() == null ? "tool-result-" + index : message.toolCallId();
        var excerpt = selectExcerpts(message.content(), focus, excerptBudget(message.content()));
        String ref = persistArtifact(message, id, excerpt);
        String replacement = renderOffloaded(message, id, ref, excerpt);
        updated.add(new ContextMessage(message.role(), message.name(), replacement, id));
        ids.add(id);
        refs.add(ref);
        if (preview.isEmpty()) preview.append(excerpt.preview());
        changed = true;
        continue;
      }
      updated.add(message);
    }
    return new OffloadResult(updated, ids, refs, preview.toString(), changed);
  }

  private static int excerptBudget(String content) {
    return Math.max(600, Math.min(6_000, content.length() / 5));
  }

  private String persistArtifact(ContextMessage message, String toolCallId, Excerpt excerpt) {
    String title = message.name() == null ? "tool-output" : message.name();
    String outlineJson = outlineJson(excerpt);
    for (var store : artifactStores) {
      if (store == null || !store.available()) continue;
      String ref =
          store.save(
              new ContextArtifactStore.Artifact(
                  null, toolCallId, title, message.content(), outlineJson));
      if (ref != null && !ref.isBlank()) return ref;
    }
    return deterministicReference(message.content(), title + toolCallId);
  }

  private static String deterministicReference(String content, String seed) {
    try {
      var digest =
          MessageDigest.getInstance("SHA-256")
              .digest((seed + "\n" + content).getBytes(StandardCharsets.UTF_8));
      return "art-" + HexFormat.of().formatHex(digest).substring(0, 16);
    } catch (Exception e) {
      return "art-" + Integer.toHexString((seed + content).hashCode());
    }
  }

  private static String renderOffloaded(
      ContextMessage original, String id, String ref, Excerpt excerpt) {
    int lines = (int) original.content().lines().count();
    var text = new StringBuilder();
    text.append("[Offloaded: toolCallId=")
        .append(id)
        .append(", artifactRef=")
        .append(ref)
        .append(", chars=")
        .append(original.content().length())
        .append(", lines=")
        .append(lines)
        .append(", use context_artifact_read if needed]\n");
    text.append("[ArtifactRef: ")
        .append(ref)
        .append(", 已保留最相关 ")
        .append(excerpt.windows().size())
        .append(" 段，其余可按需回查]\n");
    appendOutline(text, excerpt.outline());
    text.append("【高相关摘录】\n");
    for (var window : excerpt.windows()) {
      text.append("- L")
          .append(window.startLine())
          .append('-')
          .append(window.endLine())
          .append(": ")
          .append(compact(window.text(), 620))
          .append('\n');
    }
    return text.toString().strip();
  }

  private static void appendOutline(StringBuilder text, List<Outline> outline) {
    if (outline.isEmpty()) return;
    text.append("【结构化大纲】\n");
    for (var line : outline.subList(0, Math.min(OUTLINE_LIMIT, outline.size())))
      text.append("- L")
          .append(line.line())
          .append(": ")
          .append(compact(line.text(), 170))
          .append('\n');
  }

  private static String outlineJson(Excerpt excerpt) {
    try {
      var rows = new ArrayList<Map<String, Object>>();
      for (var line : excerpt.outline())
        rows.add(Map.of("line", line.line(), "text", compact(line.text(), 300)));
      return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(rows);
    } catch (Exception e) {
      return "[]";
    }
  }

  // ---------------------------------------------------------------------------
  // L2：Anchored Iterative Summarization
  // ---------------------------------------------------------------------------

  private SummaryResult anchoredSummarize(
      List<ContextMessage> messages, CompactionRequest request, String focus) {
    int pinnedEnd = pinnedEnd(messages);
    int recencyStart = Math.max(pinnedEnd, roundStart(messages, request.recencyTurns()));
    if (recencyStart <= pinnedEnd) return SummaryResult.unchanged(messages);

    int previousIndex = previousCheckpointIndex(messages, pinnedEnd, recencyStart);
    int middleStart = previousIndex < 0 ? pinnedEnd : previousIndex + 1;
    var middle = messages.subList(middleStart, recencyStart);
    String previous = previousIndex < 0 ? "" : messages.get(previousIndex).content();
    if (middle.isEmpty() && previous.isBlank()) return SummaryResult.unchanged(messages);

    String summary = summarize(request, focus, previous, middle);
    if (summary.isBlank()) summary = fallbackCheckpoint(focus, previous, middle);
    if (summary.isBlank()) return SummaryResult.unchanged(messages);

    var updated = new ArrayList<ContextMessage>();
    updated.addAll(messages.subList(0, previousIndex < 0 ? pinnedEnd : previousIndex));
    updated.add(new ContextMessage("SYSTEM", null, summary, null));
    updated.addAll(messages.subList(recencyStart, messages.size()));
    return new SummaryResult(updated, summary, summary, true);
  }

  private static int previousCheckpointIndex(
      List<ContextMessage> messages, int from, int to) {
    for (int index = to - 1; index >= from; index--) {
      var message = messages.get(index);
      if ("SYSTEM".equals(message.role())
          && message.content() != null
          && message.content().contains(CHECKPOINT_MARK)) return index;
    }
    return -1;
  }

  private String summarize(
      CompactionRequest request,
      String focus,
      String previous,
      List<ContextMessage> middle) {
    var candidate =
        request.summarizer() != null && request.summarizer().isAvailable()
            ? request.summarizer()
            : firstAvailable();
    if (candidate == null) return "";
    try {
      String text = candidate.summarize(focus, previous, middle);
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
        if (line.endsWith("?") || line.endsWith("？")
            || line.contains("待办") || lower.contains("todo") || lower.contains("next step")
            || line.contains("未解决") || line.contains("下一步")) {
          addLimited(questions, line);
        } else if (line.contains("禁止") || line.contains("不得") || line.contains("必须")
            || line.contains("仅允许") || line.contains("只能") || lower.contains("must")
            || lower.contains("never") || lower.contains("constraint")) {
          addLimited(constraints, line);
        } else if (PATH_PATTERN.matcher(line).find()
            || line.contains("修改") || line.contains("写入") || line.contains("创建")
            || lower.contains("changed") || lower.contains("written")
            || "TOOL".equals(role)) {
          addLimited(states, line);
        } else if (NUMBER_PATTERN.matcher(line).find()
            || line.length() >= 12) {
          addLimited(facts, line);
        }
      }
    }
    var mergedFacts = merge(sections.get("confirmed_facts"), rankByFocus(facts, focus), 8);
    var mergedConstraints =
        merge(sections.get("active_constraints"), constraints, 8);
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

  private static List<String> merge(
      List<String> previous, List<String> next, int limit) {
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
    for (String line : lines) text.append("- ").append(compact(line, 210)).append('\n');
  }

  private static void addLimited(List<String> lines, String line) {
    if (lines.size() >= 10) return;
    lines.add(compact(line, 210));
  }

  // ---------------------------------------------------------------------------
  // L3：Importance-Weighted Hard Guard
  // ---------------------------------------------------------------------------

  private HardGuardResult hardGuard(
      List<ContextMessage> messages, CompactionRequest request, String focus) {
    var working = new ArrayList<>(messages);
    int pinnedEnd = pinnedEnd(working);
    int recencyStart = Math.max(pinnedEnd, roundStart(working, 1));
    if (recencyStart <= pinnedEnd) {
      recencyStart = working.size();
    }
    boolean changed = false;
    int safeLimit = Math.max(1, (int) (request.contextLimit() * 0.90));
    for (int guard = 0; guard < 200 && estimate(working) > safeLimit; guard++) {
      int candidate = lowestValueIndex(working, pinnedEnd, recencyStart, focus);
      if (candidate < 0) break;
      var message = working.get(candidate);
      if (message.toolCallId() != null) {
        int remaining = Math.max(400, (safeLimit - estimate(working)) * 4);
        var excerpt = selectExcerpts(message.content(), focus, Math.max(400, remaining));
        String content =
            "[HardGuard excerpt: chars="
                + message.content().length()
                + ", 已保留最相关 "
                + excerpt.windows().size()
                + " 段]\n"
                + renderExcerptBody(excerpt);
        working.set(
            candidate,
            new ContextMessage(message.role(), message.name(), content, message.toolCallId()));
      } else {
        working.remove(candidate);
        if (recencyStart > candidate) recencyStart--;
      }
      changed = true;
    }
    if (!changed) return HardGuardResult.unchanged(messages);
    ensureHardGuardNotice(working);
    return new HardGuardResult(
        working, HARD_GUARD_MARK + "已按相关性从低到高淘汰旧正文；成对工具关系与结构化锚点保持不变。", true);
  }

  private static int lowestValueIndex(
      List<ContextMessage> messages, int pinnedEnd, int recencyStart, String focus) {
    int candidate = -1;
    double lowest = Double.MAX_VALUE;
    var inputs = new ArrayList<HybridRanker.Input>();
    var offsets = new ArrayList<Integer>();
    for (int index = pinnedEnd; index < Math.min(recencyStart, messages.size()); index++) {
      var message = messages.get(index);
      if ("SYSTEM".equals(message.role())) continue;
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
      double score =
          ranked.containsKey("m" + index) ? ranked.get("m" + index).bm25() : 0;
      score += structuralBoost(message.content()) * 0.35;
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
    int at = Math.min(pinnedEnd(messages), messages.size());
    messages.add(
        at,
        new ContextMessage(
            "SYSTEM",
            null,
            HARD_GUARD_MARK
                + "中间历史已按相关性裁剪；任务锚点、结构化检查点与最近一轮完整消息保持不变。",
            null));
  }

  // ---------------------------------------------------------------------------
  // Query-Aware 摘录与结构化大纲
  // ---------------------------------------------------------------------------

  record Outline(int line, String text) {}

  record Window(int startLine, int endLine, String text) {}

  record Excerpt(List<Window> windows, List<Outline> outline) {
    String preview() {
      if (windows.isEmpty()) return "";
      var text = new StringBuilder();
      for (var window : windows) {
        text.append("L")
            .append(window.startLine())
            .append('-')
            .append(window.endLine())
            .append(": ")
            .append(compact(window.text(), 220))
            .append('\n');
      }
      String value = text.toString();
      return value.length() <= L1_PREVIEW_CHARS ? value : value.substring(0, L1_PREVIEW_CHARS - 1) + "…";
    }
  }

  private static Excerpt selectExcerpts(String content, String focus, int budgetChars) {
    if (content == null || content.isBlank()) return new Excerpt(List.of(), List.of());
    var windows = windows(content);
    var outline = outline(content);
    if (windows.isEmpty()) return new Excerpt(List.of(), outline);
    var inputs = new ArrayList<HybridRanker.Input>();
    for (int index = 0; index < windows.size(); index++)
      inputs.add(new HybridRanker.Input("w" + index, windows.get(index).text(), 0));
    var ranked = HybridRanker.rank(inputs, focus);
    var scored = new ArrayList<ScoredWindow>();
    for (int index = 0; index < windows.size(); index++) {
      var window = windows.get(index);
      double lexical = ranked.containsKey("w" + index) ? ranked.get("w" + index).bm25() : 0;
      double score = 0.75 * lexical + 0.25 * structuralBoost(window.text());
      scored.add(new ScoredWindow(window, score, index));
    }
    scored.sort(
        Comparator.comparingDouble(ScoredWindow::score)
            .reversed()
            .thenComparingInt(ScoredWindow::index));
    var chosen = new ArrayList<Window>();
    int used = 0;
    for (var item : scored) {
      if (!chosen.isEmpty() && used + item.window().text().length() > budgetChars) continue;
      chosen.add(item.window());
      used += item.window().text().length();
      if (used >= budgetChars) break;
    }
    if (chosen.isEmpty()) chosen.add(windows.getFirst());
    chosen.sort(Comparator.comparingInt(Window::startLine));
    return new Excerpt(List.copyOf(chosen), outline);
  }

  private static List<Window> windows(String content) {
    String text = content.replace("\r\n", "\n").replace('\r', '\n');
    var lines = text.split("\n", -1);
    var result = new ArrayList<Window>();
    var paragraph = new StringBuilder();
    int start = -1;
    for (int index = 0; index < lines.length; index++) {
      String line = lines[index];
      if (line.isBlank()) {
        flushWindow(result, paragraph, start, index);
        start = -1;
        continue;
      }
      if (start < 0) start = index + 1;
      paragraph.append(line).append('\n');
      if (paragraph.length() >= L1_WINDOW_CHARS * 2) {
        flushWindow(result, paragraph, start, index + 1);
        start = -1;
      }
    }
    flushWindow(result, paragraph, start, lines.length);
    if (result.isEmpty() && !text.isBlank())
      result.add(new Window(1, 1 + (int) text.chars().filter(c -> c == '\n').count(), text));
    return List.copyOf(result);
  }

  private static void flushWindow(
      List<Window> output, StringBuilder paragraph, int startLine, int endLine) {
    if (paragraph.length() == 0 || startLine < 1) {
      paragraph.setLength(0);
      return;
    }
    String body = paragraph.toString().strip();
    paragraph.setLength(0);
    if (body.isBlank()) return;
    if (body.length() <= L1_WINDOW_CHARS) {
      output.add(new Window(startLine, Math.max(startLine, endLine), body));
      return;
    }
    int offset = 0;
    while (offset < body.length()) {
      int end = Math.min(body.length(), offset + L1_WINDOW_CHARS);
      if (end < body.length()) {
        int newline = body.lastIndexOf('\n', end);
        if (newline > offset + L1_WINDOW_CHARS / 2) end = newline;
      }
      String part = body.substring(offset, end).strip();
      if (!part.isBlank()) {
        int lineOffset = (int) body.substring(0, offset).chars().filter(c -> c == '\n').count();
        output.add(
            new Window(
                startLine + lineOffset,
                startLine + lineOffset + (int) part.chars().filter(c -> c == '\n').count(),
                part));
      }
      if (end >= body.length()) break;
      offset = Math.max(offset + 1, end - 60);
    }
  }

  private static List<Outline> outline(String content) {
    var result = new ArrayList<Outline>();
    var lines = content.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
    for (int index = 0; index < lines.length && result.size() < OUTLINE_LIMIT * 2; index++) {
      String line = lines[index].strip();
      if (line.isBlank()) continue;
      String lower = line.toLowerCase(Locale.ROOT);
      boolean heading = line.matches("^#{1,6}\\s+.+") || line.matches("^[=~-]{3,}$");
      boolean code =
          line.matches("^(public|private|protected|static|class|interface|record|def|function|export|const|let)\\b.*")
              || line.contains("=>")
              || line.endsWith("{");
      boolean signal =
          PATH_PATTERN.matcher(line).find()
              || EXCEPTION_PATTERN.matcher(line).find()
              || lower.contains("todo");
      if (heading || code || signal) result.add(new Outline(index + 1, line));
    }
    return List.copyOf(result);
  }

  private static double structuralBoost(String content) {
    if (content == null || content.isBlank()) return 0;
    double score = 0;
    if (PATH_PATTERN.matcher(content).find()) score += 0.35;
    if (EXCEPTION_PATTERN.matcher(content).find()) score += 0.45;
    if (NUMBER_PATTERN.matcher(content).find()) score += 0.15;
    if (content.contains("TODO") || content.contains("待办") || content.contains("未解决"))
      score += 0.2;
    if (content.contains("禁止") || content.contains("不得") || content.contains("必须"))
      score += 0.2;
    return Math.min(1, score);
  }

  private static String renderExcerptBody(Excerpt excerpt) {
    var text = new StringBuilder();
    for (var window : excerpt.windows()) {
      text.append("- L")
          .append(window.startLine())
          .append('-')
          .append(window.endLine())
          .append(": ")
          .append(compact(window.text(), 620))
          .append('\n');
    }
    return text.toString();
  }

  private static String compact(String value, int max) {
    if (value == null) return "";
    String oneLine = value.replace('\n', ' ').strip();
    return oneLine.length() <= max ? oneLine : oneLine.substring(0, max - 1) + "…";
  }

  private static int pinnedEnd(List<ContextMessage> messages) {
    int index = 0;
    while (index < messages.size() && "SYSTEM".equals(messages.get(index).role())) index++;
    if (index < messages.size() && "USER".equals(messages.get(index).role())) index++;
    return Math.max(1, index);
  }

  private record ScoredWindow(Window window, double score, int index) {}

  private record OffloadResult(
      List<ContextMessage> messages,
      List<String> ids,
      List<String> refs,
      String preview,
      boolean changed) {}

  private record SummaryResult(
      List<ContextMessage> messages, String preview, String anchor, boolean changed) {
    static SummaryResult unchanged(List<ContextMessage> messages) {
      return new SummaryResult(messages, "", "", false);
    }
  }

  private record HardGuardResult(
      List<ContextMessage> messages, String preview, boolean changed) {
    static HardGuardResult unchanged(List<ContextMessage> messages) {
      return new HardGuardResult(messages, "", false);
    }
  }
}
