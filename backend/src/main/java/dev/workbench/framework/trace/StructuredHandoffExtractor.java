package dev.workbench.framework.trace;

import dev.workbench.framework.prompt.PromptResourceLoader;
import dev.workbench.framework.trace.GlassboxModels.GatewayCheck;
import dev.workbench.framework.trace.GlassboxModels.StructuredHandoff;
import dev.workbench.framework.workflow.plan.WorkflowHandoff;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 从持久化交接记录中确定性提取结构化交接信封，兼容历史自由文本交接与新版结构化输出。 */
public final class StructuredHandoffExtractor {
  private static final Pattern FILE_PATTERN =
      Pattern.compile("([A-Za-z0-9_./-]+\\.(?:java|js|jsx|ts|tsx|py|html|css|md|json|sql|yml|yaml))");
  private static final Pattern MARKDOWN_HEADING = Pattern.compile("^#{1,6}\\s+.*$");
  private static final Pattern TABLE_SEPARATOR = Pattern.compile("^\\|?\\s*:?-{3,}.*$");
  private static final Pattern TABLE_ROW = Pattern.compile("^\\|.*\\|$");

  private static final List<String> DECISION_MARKERS_ZH =
      List.of("决定", "方案", "采用", "实现", "结论", "设计", "选择");
  private static final List<String> DECISION_MARKERS_EN =
      List.of(
          "decision",
          "approach",
          "strategy",
          "implement",
          "design",
          "adopt",
          "chosen",
          "conclusion",
          "selected");
  private static final List<String> RISK_MARKERS_ZH =
      List.of("风险", "待确认", "未验证", "注意", "阻塞", "遗留", "待办", "未知");
  private static final List<String> RISK_MARKERS_EN =
      List.of(
          "risk",
          "todo",
          "pending",
          "unverified",
          "caveat",
          "warning",
          "blocker",
          "open question",
          "open issue",
          "assumption");
  private static final List<String> SUMMARY_MARKERS_ZH =
      List.of("结论", "摘要", "总结", "核心", "分析完成", "建议");
  private static final List<String> SUMMARY_MARKERS_EN =
      List.of("summary", "conclusion", "finding", "core", "completed", "recommend");

  private StructuredHandoffExtractor() {}

  public static StructuredHandoff extract(WorkflowHandoff handoff) {
    return extract(handoff, PromptResourceLoader.ZH_CN);
  }

  /** 按语言生成校验项标题与说明；交接正文按原样提取，不翻译历史业务内容。 */
  public static StructuredHandoff extract(WorkflowHandoff handoff, String locale) {
    boolean english = PromptResourceLoader.isEnglish(locale);
    String raw = handoff.sourceResult() == null ? "" : handoff.sourceResult().trim();
    List<String> lines =
        raw.isEmpty()
            ? List.of()
            : raw.lines().map(String::trim).filter(line -> !line.isEmpty()).toList();
    String summary =
        lines.isEmpty()
            ? english ? "The upstream node did not record a handoff body." : "上游未记录交接正文。"
            : truncate(pickSummary(lines), 220);

    var decisions = new ArrayList<String>();
    var risks = new ArrayList<String>();
    var artifacts = new LinkedHashSet<String>();

    for (String line : lines) {
      if (isStructuralLine(line)) continue;
      String lower = line.toLowerCase(Locale.ROOT);
      if (containsAny(line, DECISION_MARKERS_ZH) || containsAny(lower, DECISION_MARKERS_EN)) {
        if (decisions.size() < 5) decisions.add(truncate(line, 180));
      }
      if (containsAny(line, RISK_MARKERS_ZH) || containsAny(lower, RISK_MARKERS_EN)) {
        if (risks.size() < 5) risks.add(truncate(line, 180));
      }
      Matcher matcher = FILE_PATTERN.matcher(line);
      while (matcher.find() && artifacts.size() < 12) {
        artifacts.add(matcher.group(1));
      }
    }

    for (var ev : handoff.toolEvidence()) {
      if (ev.resourceVersion() != null) {
        Matcher matcher = FILE_PATTERN.matcher(ev.resourceVersion());
        while (matcher.find() && artifacts.size() < 12) {
          artifacts.add(matcher.group(1));
        }
      }
    }

    var checks = new ArrayList<GatewayCheck>();
    checks.add(
        new GatewayCheck(
            "DEPENDENCY_MATCH",
            english ? "Direct upstream dependency match" : "直接上游依赖匹配",
            handoff.dependencyNodeId() != null ? "PASS" : "SKIP",
            handoff.dependencyNodeId() != null
                ? english
                    ? "Source node "
                        + handoff.senderNodeId()
                        + " matches a planned dependency edge"
                    : "来源节点 " + handoff.senderNodeId() + " 命中计划依赖边"
                : english ? "Not a DAG dependency edge" : "非 DAG 依赖边"));
    checks.add(
        new GatewayCheck(
            "ATTEMPT_IDENTITY",
            english ? "Instance and attempt ownership" : "实例与尝试归属核对",
            handoff.senderAttemptId() != null && handoff.receiverAttemptId() != null
                ? "PASS"
                : "DENY",
            english
                ? "Both sender and receiver attempts are bound to persisted IDs"
                : "发送实例与接收尝试均已绑定持久化 ID"));
    checks.add(
        new GatewayCheck(
            "TOOL_EVIDENCE",
            english ? "Tool and file evidence attached" : "工具与文件证据随行",
            !handoff.toolEvidence().isEmpty() ? "PASS" : "SKIP",
            !handoff.toolEvidence().isEmpty()
                ? english
                    ? "Handoff carries "
                        + handoff.toolEvidence().size()
                        + " settled tool evidence records"
                    : "随交接附带 " + handoff.toolEvidence().size() + " 条已结算工具证据"
                : english
                    ? "Text-only conclusion; no tool evidence attached"
                    : "本次交接为纯文本结论，无附带工具证据"));
    checks.add(
        new GatewayCheck(
            "ACCEPTED_RECEIPT",
            english ? "Downstream accepted into context" : "下游接收并纳入上下文",
            handoff.acceptedAt() != null ? "PASS" : "DENY",
            handoff.acceptedAt() != null
                ? english
                    ? "Written into downstream input at " + handoff.acceptedAt()
                    : "已于 " + handoff.acceptedAt() + " 写入下游执行输入"
                : english ? "Receipt not confirmed yet" : "尚未确认接收"));

    return new StructuredHandoff(
        summary, decisions, List.copyOf(artifacts), risks, List.copyOf(checks));
  }

  /** 跳过 Markdown 标题、表格分隔行等结构行，优先选择包含实质结论的正文行。 */
  private static String pickSummary(List<String> lines) {
    List<String> substantive = lines.stream().filter(line -> !isStructuralLine(line)).toList();
    if (substantive.isEmpty()) return lines.getFirst();
    for (String line : substantive) {
      String lower = line.toLowerCase(Locale.ROOT);
      if (containsAny(line, SUMMARY_MARKERS_ZH) || containsAny(lower, SUMMARY_MARKERS_EN))
        return line;
    }
    return substantive.getFirst();
  }

  private static boolean isStructuralLine(String line) {
    String text = line.strip();
    if (text.isEmpty()) return true;
    if (MARKDOWN_HEADING.matcher(text).matches()) return true;
    if (TABLE_SEPARATOR.matcher(text).matches()) return true;
    // 只有表头/边框、没有实际文字的表格行不构成结论。
    return TABLE_ROW.matcher(text).matches()
        && text.replace("|", "").replace("-", "").replace(":", "").isBlank();
  }

  private static boolean containsAny(String text, List<String> markers) {
    for (String marker : markers) if (text.contains(marker)) return true;
    return false;
  }

  private static String truncate(String text, int max) {
    return text.length() <= max ? text : text.substring(0, max) + "...";
  }
}
