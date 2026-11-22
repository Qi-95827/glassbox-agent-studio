package dev.workbench.framework.tool;

/** 风险分级策略；默认实现只依据工具效果与参数中的敏感路径判断。 */
public interface ToolRiskPolicy {
  ToolRiskAssessment assess(ToolRiskContext context);
}
