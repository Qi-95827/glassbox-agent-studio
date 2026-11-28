package dev.workbench.control;

import dev.workbench.framework.context.ContextMessage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 上下文分段计量：保守 Token 估算与提示词真实拼装结构的分段归桶。
 *
 * <p>该计量口径被 L0 观察快照与 L1/L2/L3 各级共用，任何改动都会直接改变玻璃盒里的六色占比。
 */
final class ContextSegmentEstimator {
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

  private ContextSegmentEstimator() {}

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

  /** 整条消息列表的六色分段汇总。 */
  static Map<String, Integer> segments(List<ContextMessage> messages) {
    var result = new LinkedHashMap<String, Integer>();
    for (var message : messages) {
      for (var entry : segmentTokens(message).entrySet())
        result.merge(entry.getKey(), entry.getValue(), Integer::sum);
    }
    return result;
  }

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
    // StepInput 结构化 JSON 仅出现在 USER 输入消息中；绝不误拆 TOOL/SYSTEM/ASSISTANT 里的任意业务 JSON。
    if ("USER".equals(message.role())) {
      var structured = segmentStructuredJson(content, defaultSegment);
      if (structured != null) return structured;
    }
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

  /** 未命中任何结构化标记时的默认段：工具输出、系统规则与普通历史。 */
  private static String segmentOf(ContextMessage message) {
    String content = message.content() == null ? "" : message.content();
    if ("TOOL".equals(message.role()) || "TOOL_OBSERVATION".equals(message.role()))
      return "TOOL_OUTPUTS";
    if ("SYSTEM".equals(message.role())) return "SYSTEM";
    return "HISTORY";
  }
}
