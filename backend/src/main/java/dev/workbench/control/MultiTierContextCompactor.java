package dev.workbench.control;

import dev.workbench.framework.context.CompactionRequest;
import dev.workbench.framework.context.CompactionResult;
import dev.workbench.framework.context.CompactionScope;
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
 * <p><b>L0</b> 只做精确分桶；<b>L1</b> 把超长工具输出完整落盘为 Artifact，并依据当前目标问题 选出最相关段落留在消息体，彻底废除“死留头 200 + 尾 100
 * 字”；<b>L2</b> 保留固定 System 锚点与最近 成对轮次，把中间历史压缩为可增量合并的四段结构化检查点；<b>L3</b> 仅在仍超限时按相关性从低到高 淘汰正文，绝不删除
 * System 规则、结构化锚点，也绝不拆散 {@code tool_call}/{@code tool_result} 成对关系。
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
    var offloadDetails = new ArrayList<ContextBreakdown.OffloadDetail>();

    if (ratio(before, limit) >= request.l1Threshold()) {
      var result = offloadToolOutputs(working, request.offloadAfterTurns(), focus, request.scope());
      if (result.changed()) {
        working = result.messages();
        offloaded.addAll(result.ids());
        artifactRefs.addAll(result.refs());
        offloadDetails.addAll(result.details());
        retainedPreview = result.preview();
        tier = CompactionTier.L1_TOOL_OFFLOAD;
      }
    }
    int afterL1 = estimate(working);
    int mergedFrom = -1;
    int mergedTo = -1;
    int mergedCount = 0;
    if (ratio(afterL1, limit) >= request.l2Threshold()) {
      // 合并范围要在摘要发生前留底：摘要后原消息已被替换，无法再回推是哪一段被压掉了。
      var beforeL2 = List.copyOf(working);
      var result = anchoredSummarize(working, request, focus);
      if (result.changed()) {
        working = result.messages();
        summaryPreview = result.preview();
        structuredAnchors = result.anchor();
        tier = CompactionTier.L2_SLIDING_SUMMARY;
        var surviving = new java.util.HashSet<Integer>();
        for (var message : working)
          if (message.originalIndex() != null) surviving.add(message.originalIndex());
        for (var message : beforeL2) {
          if (message.originalIndex() == null || surviving.contains(message.originalIndex()))
            continue;
          mergedCount++;
          int index = message.originalIndex();
          if (mergedFrom < 0 || index < mergedFrom) mergedFrom = index;
          if (index > mergedTo) mergedTo = index;
        }
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

    // L3 之后仍然保留的工具调用标识，就是被硬保底保护下来的「原子对」名单。
    // 只有真正触发 L3 才有这份明细：L0/L1/L2 不应把「当前仍在上下文里的工具调用」冒充成 L3 保留结果。
    var protectedToolCalls = new ArrayList<String>();
    if (tier == CompactionTier.L3_HARD_GUARD) {
      for (var message : working)
        if (message.toolCallId() != null && !protectedToolCalls.contains(message.toolCallId()))
          protectedToolCalls.add(message.toolCallId());
    }

    int total = estimate(working);
    // 预览与锚点会被持久化到 model_call_payloads 并展示在玻璃盒里：落库前统一脱敏，
    // 避免工具输出摘录或结构化锚点把模型 Key / Bearer 令牌原样带进快照。
    summaryPreview = dev.workbench.framework.trace.SensitiveDataMasker.mask(summaryPreview);
    retainedPreview = dev.workbench.framework.trace.SensitiveDataMasker.mask(retainedPreview);
    structuredAnchors = dev.workbench.framework.trace.SensitiveDataMasker.mask(structuredAnchors);
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
            structuredAnchors,
            new ContextBreakdown.CompactionDetail(
                List.copyOf(offloadDetails),
                mergedFrom,
                mergedTo,
                mergedCount,
                List.copyOf(protectedToolCalls)));
    return new CompactionResult(working, breakdown);
  }

  private static double ratio(int tokens, int limit) {
    return limit < 1 ? 0 : (double) tokens / limit;
  }

  /**
   * 保守 Token 估算：ASCII 约 4 字符/token，非 ASCII（中文、全角标点）按 1.5 token/字符。
   *
   * <p>旧实现统一按字符/4 估算，一段纯中文会被低估 3~6 倍：水位迟迟不触发，真正超限时只能靠供应商 返回 400。中英混排按各自口径分别累加，宁可略微高估也不低估。
   */
  static int estimate(List<ContextMessage> messages) {
    long total = 0;
    for (var message : messages)
      total += estimateText(message.content() == null ? "" : message.content());
    return (int) Math.min(Integer.MAX_VALUE, Math.max(0, total));
  }

  /** 单段文本的保守 Token 估算；分段计数与整条计数共用同一口径。 */
  static int estimateText(String content) {
    long ascii = 0;
    long wide = 0;
    for (int index = 0; index < content.length(); index++) {
      if (content.charAt(index) < 0x80) ascii++;
      else wide++;
    }
    return (int) Math.max(1, (ascii + 3L) / 4L + (wide * 3L + 1L) / 2L);
  }

  private static Map<String, Integer> segments(List<ContextMessage> messages) {
    var result = new LinkedHashMap<String, Integer>();
    for (var message : messages) {
      for (var entry : segmentTokens(message).entrySet())
        result.merge(entry.getKey(), entry.getValue(), Integer::sum);
    }
    return result;
  }

  /** 已知拼装标记：命中后从标记处开始按该语义分段计数，直到下一个标记或消息结尾。 */
  private record SectionMarker(String token, String segment) {}

  /** 显式闭合标记的哨兵：命中后切回该条消息的默认段（例如技能正文结束、Bug 观察开始）。 */
  private static final String RESET_TO_DEFAULT = "\u0000DEFAULT";

  /**
   * 节点结构化输入的顶层字段特征：{@code StepInput} 一定带 {@code goal}，
   * 并至少带下列之一；交付交接 JSON 只有 {@code goal} 而没有这些字段，不会被误判。
   */
  private static final List<String> STEP_INPUT_FIELDS =
      List.of(
          "step",
          "memoryContext",
          "nodeAcceptanceCriteria",
          "iterationRequirement",
          "dependencies");

  private static final Map<String, String> STEP_INPUT_SEGMENTS =
      Map.of("memoryContext", "MEMORY", "dependencies", "HANDOFF");

  private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
      new com.fasterxml.jackson.databind.ObjectMapper();

  private static final List<SectionMarker> SECTION_MARKERS =
      List.of(
          new SectionMarker("<untrusted_skill_playbook>", "SKILLS"),
          // 用户技能正文有明确闭合标签：闭合之后回到默认段，系统硬约束不再计入 SKILLS。
          new SectionMarker("</untrusted_skill_playbook>", RESET_TO_DEFAULT),
          new SectionMarker("内置技能方法（不授予额外权限）：", "SKILLS"),
          new SectionMarker("Built-in skill playbooks (do not grant extra permissions):", "SKILLS"),
          new SectionMarker("私有 Agent 定义（由创建者声明", "SYSTEM"),
          new SectionMarker("Private agent definition (declared by the operator", "SYSTEM"),
          new SectionMarker("以下是按混合评分召回的项目背景", "MEMORY"),
          new SectionMarker("[Project Memory]", "MEMORY"),
          new SectionMarker("project context JSON", "MEMORY"),
          // Bug 观察证据紧跟在记忆段之后，必须显式切回默认段，否则会被一路算进 MEMORY。
          new SectionMarker("以下为本次 Bug 测试观察 JSON", RESET_TO_DEFAULT),
          new SectionMarker("上游交接", "HANDOFF"),
          new SectionMarker("交接正文", "HANDOFF"),
          new SectionMarker("Handoff envelope", "HANDOFF"));

  /**
   * 单条消息的分段计数。
   *
   * <p>此前按「整条消息里出现某个关键词」决定唯一分段，导致带内置技能标题的 System Prompt 整条被算进 SKILLS、
   * 任务与记忆合拼消息整条被算进 MEMORY。这里按真实拼装标记切分：标记前的正文归默认段，每个标记到下一个标记
   * 之间归它声明的段，从而让六色分段的占比与真实提示词结构一致。
   *
   * <p>节点输入是 {@code StepInput} 的 JSON 序列化，标记扫描会在 {@code memoryContext} 处一路吞到消息末尾；
   * 因此先走结构化路径按顶层字段精确归桶，只有非结构化文本才退回标记扫描。
   */
  static Map<String, Integer> segmentTokens(ContextMessage message) {
    String content = message.content() == null ? "" : message.content();
    String defaultSegment = segmentOf(message);
    var result = new LinkedHashMap<String, Integer>();
    if (content.isEmpty()) {
      result.merge(defaultSegment, 0, Integer::sum);
      return result;
    }
    var structured = segmentStructuredJson(content, defaultSegment);
    if (structured != null) return structured;
    var hits = new ArrayList<int[]>();
    var hitSegments = new ArrayList<String>();
    for (var marker : SECTION_MARKERS) {
      int from = 0;
      while (from < content.length()) {
        int index = content.indexOf(marker.token(), from);
        if (index < 0) break;
        hits.add(new int[] {index, index + marker.token().length()});
        hitSegments.add(marker.segment());
        from = index + marker.token().length();
      }
    }
    if (hits.isEmpty()) {
      result.merge(defaultSegment, estimateText(content), Integer::sum);
      return result;
    }
    var order = new ArrayList<Integer>();
    for (int index = 0; index < hits.size(); index++) order.add(index);
    order.sort(java.util.Comparator.comparingInt(index -> hits.get(index)[0]));

    int cursor = 0;
    for (int position = 0; position < order.size(); position++) {
      int hit = order.get(position);
      int start = hits.get(hit)[0];
      if (start < cursor) continue; // 重叠标记只保留最早出现的那个
      if (start > cursor)
        result.merge(defaultSegment, estimateText(content.substring(cursor, start)), Integer::sum);
      int end =
          position + 1 < order.size() ? Math.max(start, hits.get(order.get(position + 1))[0]) : content.length();
      String segment = hitSegments.get(hit);
      // 显式闭合标记只负责「切回默认段」，自身不构成独立语义桶。
      if (RESET_TO_DEFAULT.equals(segment)) segment = defaultSegment;
      result.merge(segment, estimateText(content.substring(start, end)), Integer::sum);
      cursor = Math.max(cursor, end);
    }
    if (cursor < content.length())
      result.merge(defaultSegment, estimateText(content.substring(cursor)), Integer::sum);
    return result;
  }

  /**
   * 结构化优先：把 {@code StepInput} JSON 的顶层字段按语义精确归桶。
   *
   * <p>只认「{@code goal} + 至少一个节点输入特征字段」的形态，交付交接 JSON 等其它对象仍走标记扫描。
   *
   * @return 归桶结果；不是节点结构化输入时返回 {@code null}
   */
  private static Map<String, Integer> segmentStructuredJson(String content, String defaultSegment) {
    if (!content.stripLeading().startsWith("{")) return null;
    com.fasterxml.jackson.databind.JsonNode root;
    try {
      root = JSON.readTree(content);
    } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
      return null;
    }
    if (root == null || !root.isObject()) return null;
    var goal = root.get("goal");
    if (goal == null || !goal.isTextual()) return null;
    boolean stepInput = false;
    for (String field : STEP_INPUT_FIELDS) {
      if (root.has(field)) {
        stepInput = true;
        break;
      }
    }
    if (!stepInput) return null;
    var weights = new LinkedHashMap<String, Integer>();
    var fields = root.fields();
    while (fields.hasNext()) {
      var entry = fields.next();
      if (isEmptyJson(entry.getValue())) continue;
      String segment = STEP_INPUT_SEGMENTS.getOrDefault(entry.getKey(), defaultSegment);
      weights.merge(
          segment, estimateText("\"" + entry.getKey() + "\":" + entry.getValue()), Integer::sum);
    }
    if (weights.isEmpty()) return null;
    return balanceToTotal(weights, defaultSegment, estimateText(content));
  }

  /** 空字符串、空数组、空对象与 null 不参与计量，避免大量 {@code Math.max(1, ...)} 虚高毛刺。 */
  private static boolean isEmptyJson(com.fasterxml.jackson.databind.JsonNode value) {
    if (value == null || value.isNull()) return true;
    if (value.isTextual()) return value.asText().isBlank();
    if (value.isArray() || value.isObject()) return value.isEmpty();
    return false;
  }

  /**
   * 把逐字段计量与整条消息估算的差值（JSON 语法开销与逐段取整误差）统一归默认段。
   *
   * <p>不做等比缩放：{@code MEMORY} 必须只由 {@code memoryContext} 决定，否则验收条件或调改要求一变大，
   * 记忆桶就会被一起抬高，等于又回到「串段」。
   */
  private static Map<String, Integer> balanceToTotal(
      Map<String, Integer> weights, String defaultSegment, int total) {
    var result = new LinkedHashMap<>(weights);
    int sum = 0;
    for (int value : result.values()) sum += value;
    int residual = total - sum;
    if (residual == 0 || result.isEmpty()) return result;
    String target =
        result.containsKey(defaultSegment)
            ? defaultSegment
            : result.keySet().iterator().next();
    result.put(target, Math.max(0, result.get(target) + residual));
    return result;
  }

  private static String segmentOf(ContextMessage message) {
    String content = message.content() == null ? "" : message.content();
    if ("TOOL".equals(message.role()) || "TOOL_OBSERVATION".equals(message.role()))
      return "TOOL_OUTPUTS";
    if ("SYSTEM".equals(message.role())) return "SYSTEM";
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
      if ("USER".equals(message.role())
          && message.content() != null
          && !message.content().isBlank()) return message.content().strip();
    }
    return "";
  }

  // ---------------------------------------------------------------------------
  // L1：Artifact 落盘 + Query-Aware 高相关片段摘录
  // ---------------------------------------------------------------------------

  private OffloadResult offloadToolOutputs(
      List<ContextMessage> messages, int keepTurns, String focus, CompactionScope scope) {
    int keepFrom = roundStart(messages, keepTurns);
    var updated = new ArrayList<ContextMessage>(messages.size());
    var ids = new ArrayList<String>();
    var refs = new ArrayList<String>();
    var details = new ArrayList<ContextBreakdown.OffloadDetail>();
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
        String ref = persistArtifact(message, id, excerpt, scope);
        String replacement = renderOffloaded(message, id, ref, excerpt);
        updated.add(new ContextMessage(message.role(), message.name(), replacement, id));
        ids.add(id);
        refs.add(ref);
        details.add(
            new ContextBreakdown.OffloadDetail(
                id, ref, message.content().length(), replacement.length()));
        if (preview.isEmpty()) preview.append(excerpt.preview());
        changed = true;
        continue;
      }
      updated.add(message);
    }
    return new OffloadResult(updated, ids, refs, preview.toString(), changed, details);
  }

  private static int excerptBudget(String content) {
    return Math.max(600, Math.min(6_000, content.length() / 5));
  }

  private String persistArtifact(
      ContextMessage message, String toolCallId, Excerpt excerpt, CompactionScope scope) {
    String title = message.name() == null ? "tool-output" : message.name();
    String outlineJson = outlineJson(excerpt);
    for (var store : artifactStores) {
      if (store == null || !store.available()) continue;
      String ref =
          store.save(
              new ContextArtifactStore.Artifact(
                  scope.runId(),
                  scope.ownerScopeKey(),
                  toolCallId,
                  title,
                  message.content(),
                  outlineJson));
      if (ref != null && !ref.isBlank()) return ref;
    }
    // 没有可用存储时仍要生成确定性引用，但种子必须带上运行与归属，避免跨用户撞同一引用。
    return deterministicReference(
        message.content(), title + toolCallId + scope.runId() + scope.ownerScopeKey());
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
        .append(", 可按 artifactRef 回查]\n");
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
    // 切分点必须落在工具调用原子组的边界：绝不让 assistant(tool_use) 与其 TOOL 结果分处两侧。
    recencyStart = Math.max(pinnedEnd, alignCut(messages, recencyStart));

    int previousIndex = previousCheckpointIndex(messages, pinnedEnd, recencyStart);
    int middleStart =
        Math.max(pinnedEnd, alignCut(messages, previousIndex < 0 ? pinnedEnd : previousIndex + 1));
    recencyStart = Math.max(middleStart, recencyStart);
    var middle = messages.subList(middleStart, recencyStart);
    String previous = previousIndex < 0 ? "" : messages.get(previousIndex).content();
    // 同一运行上一轮的结构化检查点参与增量合并，避免每轮都从零重写。
    String inherited = request.previousCheckpoint();
    if (inherited != null && !inherited.isBlank())
      previous = previous.isBlank() ? inherited : inherited + "\n\n" + previous;
    if (middle.isEmpty()) return SummaryResult.unchanged(messages);

    String summary = summarize(request, focus, previous, middle);
    if (summary.isBlank()) summary = fallbackCheckpoint(focus, previous, middle);
    if (summary.isBlank()) return SummaryResult.unchanged(messages);

    // 检查点只能出现在首条 USER 之前：严格角色序列模型不接受中途插入 SYSTEM。
    // 旧检查点必须一并替换掉（无论它已经在头部还是仍在中间），否则同一轮会出现两个检查点。
    int prefixEnd = previousIndex < 0 ? middleStart : Math.max(previousIndex, pinnedEnd);
    var head = new ArrayList<ContextMessage>(prefixEnd);
    for (int index = 0; index < prefixEnd; index++) {
      if (index == previousIndex) continue;
      head.add(messages.get(index));
    }
    int insertAt = Math.min(leadingSystemEnd(messages), head.size());
    head.add(insertAt, new ContextMessage("SYSTEM", null, summary, null));
    var updated = new ArrayList<ContextMessage>(head);
    updated.addAll(messages.subList(recencyStart, messages.size()));
    return new SummaryResult(updated, summary, summary, true);
  }

  /** 首段连续 SYSTEM 的结束位置；检查点与硬保护通知只能插在这里，保证首条 USER 之前没有新 SYSTEM。 */
  private static int leadingSystemEnd(List<ContextMessage> messages) {
    int index = 0;
    while (index < messages.size() && "SYSTEM".equals(messages.get(index).role())) index++;
    return index;
  }

  /**
   * 把切分点左移到工具调用原子组的边界。
   *
   * <p>若切分点上正好是 TOOL 结果，说明它的 assistant(tool_use) 会被留在另一侧；左移直到该位置不再是 TOOL。这样任何一侧都只会包含完整的「调用 + 结果」组。
   */
  static int alignCut(List<ContextMessage> messages, int cut) {
    int index = Math.max(0, Math.min(cut, messages.size()));
    while (index > 0 && index < messages.size() && messages.get(index).toolResult()) index--;
    return index;
  }

  private static int previousCheckpointIndex(List<ContextMessage> messages, int from, int to) {
    // 从列表头部开始找：上一轮可能已把检查点提升到首条 USER 之前，只从 pinnedEnd 起找会漏掉它，
    // 从而在同一轮里叠加出第二个检查点。只有带检查点标记的 SYSTEM 才会命中。
    for (int index = to - 1; index >= 0; index--) {
      if (index < from && index >= leadingSystemEnd(messages)) continue;
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
        } else if (PATH_PATTERN.matcher(line).find()
            || line.contains("修改")
            || line.contains("写入")
            || line.contains("创建")
            || lower.contains("changed")
            || lower.contains("written")
            || "TOOL".equals(role)) {
          addLimited(states, line);
        } else if (NUMBER_PATTERN.matcher(line).find() || line.length() >= 12) {
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
    recencyStart = Math.max(pinnedEnd, alignCut(working, recencyStart));
    if (recencyStart <= pinnedEnd) {
      recencyStart = working.size();
    }
    boolean changed = false;
    int safeLimit = Math.max(1, (int) (request.contextLimit() * 0.90));
    // 已处理过的消息不再重复挑选：旧实现在单条消息上反复叠加前缀，体积不减时空转 200 次。
    var processed =
        java.util.Collections.newSetFromMap(
            new java.util.IdentityHashMap<ContextMessage, Boolean>());
    for (int guard = 0; guard < 200 && estimate(working) > safeLimit; guard++) {
      int candidate = lowestValueIndex(working, pinnedEnd, recencyStart, focus, processed);
      if (candidate < 0) break;
      var message = working.get(candidate);
      int before = estimate(working);
      if (message.toolResult()) {
        // 工具结果只缩短不删除：删掉它会让对应的 assistant(tool_use) 变成孤儿并触发 400。
        int remaining = Math.max(400, (safeLimit - before) * 4);
        var excerpt = selectExcerpts(message.content(), focus, Math.max(400, remaining));
        String content =
            "[HardGuard excerpt: chars="
                + message.content().length()
                + ", 已保留最相关 "
                + excerpt.windows().size()
                + " 段]\n"
                + renderExcerptBody(excerpt);
        var shrunk =
            new ContextMessage(message.role(), message.name(), content, message.toolCallId());
        working.set(candidate, shrunk);
        if (estimate(working) >= before) {
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
    if (!changed) return HardGuardResult.unchanged(messages);
    ensureHardGuardNotice(working);
    return new HardGuardResult(working, HARD_GUARD_MARK + "已按相关性从低到高淘汰旧正文；成对工具关系与结构化锚点保持不变。", true);
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
    int at = Math.min(leadingSystemEnd(messages), messages.size());
    messages.add(
        at,
        new ContextMessage(
            "SYSTEM", null, HARD_GUARD_MARK + "中间历史已按相关性裁剪；任务锚点、结构化检查点与最近一轮完整消息保持不变。", null));
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
      return value.length() <= L1_PREVIEW_CHARS
          ? value
          : value.substring(0, L1_PREVIEW_CHARS - 1) + "…";
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
          line.matches(
                  "^(public|private|protected|static|class|interface|record|def|function|export|const|let)\\b.*")
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
    if (content.contains("TODO") || content.contains("待办") || content.contains("未解决")) score += 0.2;
    if (content.contains("禁止") || content.contains("不得") || content.contains("必须")) score += 0.2;
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
      boolean changed,
      List<ContextBreakdown.OffloadDetail> details) {}

  private record SummaryResult(
      List<ContextMessage> messages, String preview, String anchor, boolean changed) {
    static SummaryResult unchanged(List<ContextMessage> messages) {
      return new SummaryResult(messages, "", "", false);
    }
  }

  private record HardGuardResult(List<ContextMessage> messages, String preview, boolean changed) {
    static HardGuardResult unchanged(List<ContextMessage> messages) {
      return new HardGuardResult(messages, "", false);
    }
  }
}
