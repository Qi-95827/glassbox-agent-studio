package dev.workbench.framework.workflow.plan;

import java.util.List;

/**
 * 审查节点的结构化结论。
 *
 * <p>只有 {@code REVISE_REQUIRED} 才允许触发一次有界反思；正文与缺陷都视为不可信模型输出，
 * 执行侧仍需重新经过交接、工具授权与证据校验。
 */
public record ReviewVerdict(String decision, List<ReviewDefect> defects, String summary) {
  public static final String APPROVED = "APPROVED";
  public static final String REVISE_REQUIRED = "REVISE_REQUIRED";

  public ReviewVerdict {
    decision = decision == null ? APPROVED : decision.strip().toUpperCase();
    if (!APPROVED.equals(decision) && !REVISE_REQUIRED.equals(decision))
      throw new IllegalArgumentException("审查结论必须是 APPROVED 或 REVISE_REQUIRED。");
    defects = defects == null ? List.of() : List.copyOf(defects);
    summary = summary == null ? "" : summary.strip();
  }

  public boolean reviseRequired() {
    return REVISE_REQUIRED.equals(decision);
  }

  /** 供 Prompt 注入的高优先级批评反馈；不包含任何授权信息。 */
  public String feedbackText() {
    StringBuilder text = new StringBuilder();
    text.append("审查结论：").append(decision).append('\n');
    if (!summary.isBlank()) text.append("审查摘要：").append(summary).append('\n');
    for (var defect : defects) {
      text.append("- [").append(defect.severity()).append("] ");
      if (defect.filePath() != null && !defect.filePath().isBlank())
        text.append(defect.filePath()).append("：");
      text.append(defect.summary());
      if (!defect.suggestedFix().isBlank()) text.append("；建议：").append(defect.suggestedFix());
      text.append('\n');
    }
    return text.toString().strip();
  }
}
