package dev.workbench.framework.trace;

import dev.workbench.framework.trace.GlassboxModels.GatewayCheck;
import dev.workbench.framework.trace.GlassboxModels.StructuredHandoff;
import dev.workbench.framework.workflow.plan.WorkflowHandoff;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 从持久化交接记录中确定性提取结构化交接信封，兼容历史自由文本交接与新版结构化输出。 */
public final class StructuredHandoffExtractor {
  private static final Pattern FILE_PATTERN =
      Pattern.compile("([A-Za-z0-9_./-]+\\.(?:java|js|jsx|ts|tsx|py|html|css|md|json|sql|yml|yaml))");

  private StructuredHandoffExtractor() {}

  public static StructuredHandoff extract(WorkflowHandoff handoff) {
    String raw = handoff.sourceResult() == null ? "" : handoff.sourceResult().trim();
    List<String> lines =
        raw.isEmpty()
            ? List.of()
            : raw.lines().map(String::trim).filter(line -> !line.isEmpty()).toList();
    String summary = lines.isEmpty() ? "上游未记录交接正文。" : truncate(lines.get(0), 220);

    var decisions = new ArrayList<String>();
    var risks = new ArrayList<String>();
    var artifacts = new LinkedHashSet<String>();

    for (String line : lines) {
      String lower = line.toLowerCase();
      if (line.contains("决定")
          || line.contains("方案")
          || line.contains("采用")
          || line.contains("实现")
          || lower.contains("decision")) {
        if (decisions.size() < 5) decisions.add(truncate(line, 180));
      }
      if (line.contains("风险")
          || line.contains("待确认")
          || line.contains("未验证")
          || line.contains("注意")
          || line.contains("阻塞")
          || lower.contains("risk")
          || lower.contains("todo")) {
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
            "直接上游依赖匹配",
            handoff.dependencyNodeId() != null ? "PASS" : "SKIP",
            handoff.dependencyNodeId() != null
                ? "来源节点 " + handoff.senderNodeId() + " 命中计划依赖边"
                : "非 DAG 依赖边"));
    checks.add(
        new GatewayCheck(
            "ATTEMPT_IDENTITY",
            "实例与尝试归属核对",
            handoff.senderAttemptId() != null && handoff.receiverAttemptId() != null
                ? "PASS"
                : "DENY",
            "发送实例与接收尝试均已绑定持久化 ID"));
    checks.add(
        new GatewayCheck(
            "TOOL_EVIDENCE",
            "工具与文件证据随行",
            !handoff.toolEvidence().isEmpty() ? "PASS" : "SKIP",
            !handoff.toolEvidence().isEmpty()
                ? "随交接附带 " + handoff.toolEvidence().size() + " 条已结算工具证据"
                : "本次交接为纯文本结论，无附带工具证据"));
    checks.add(
        new GatewayCheck(
            "ACCEPTED_RECEIPT",
            "下游接收并纳入上下文",
            handoff.acceptedAt() != null ? "PASS" : "DENY",
            handoff.acceptedAt() != null
                ? "已于 " + handoff.acceptedAt() + " 写入下游执行输入"
                : "尚未确认接收"));

    return new StructuredHandoff(
        summary, decisions, List.copyOf(artifacts), risks, List.copyOf(checks));
  }

  private static String truncate(String text, int max) {
    return text.length() <= max ? text : text.substring(0, max) + "...";
  }
}
