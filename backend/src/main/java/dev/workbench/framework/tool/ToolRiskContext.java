package dev.workbench.framework.tool;

/** 风险评估输入；只包含工具名、效果和已脱敏前的参数 JSON，不包含凭据正文。 */
public record ToolRiskContext(String toolName, ToolEffect effect, String argsJson) {
  public ToolRiskContext {
    toolName = toolName == null ? "" : toolName;
    effect = effect == null ? ToolEffect.READ_ONLY : effect;
    argsJson = argsJson == null ? "{}" : argsJson;
  }
}
