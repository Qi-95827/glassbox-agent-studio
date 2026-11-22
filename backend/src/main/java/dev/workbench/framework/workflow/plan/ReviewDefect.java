package dev.workbench.framework.workflow.plan;

/** 结构化评审缺陷；严重级别由审查模型给出，但不得作为工具授权或通过证明。 */
public record ReviewDefect(String severity, String filePath, String summary, String suggestedFix) {
  public ReviewDefect {
    severity = severity == null || severity.isBlank() ? "MINOR" : severity.strip().toUpperCase();
    filePath = filePath == null ? null : filePath.strip();
    summary = summary == null ? "" : summary.strip();
    suggestedFix = suggestedFix == null ? "" : suggestedFix.strip();
  }
}
