package dev.workbench.framework.tool;

/** 风险等级与可审计原因。 */
public record ToolRiskAssessment(ToolRiskLevel level, String reason) {
  public ToolRiskAssessment {
    level = level == null ? ToolRiskLevel.MEDIUM : level;
    reason = reason == null ? "" : reason;
  }

  public boolean requiresApproval() {
    return level == ToolRiskLevel.HIGH;
  }
}
