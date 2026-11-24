package dev.workbench.framework.tool;

import java.util.List;

/**
 * 风险评估输入；只包含工具名、效果、已脱敏前的参数 JSON 与**明确的目标路径**，不包含凭据正文。
 *
 * <p>{@code targetPaths} 由调用方从真实授权目标注入（单文件闭包路径、多文件入参 path），风险策略据此判断 是否命中敏感文件；为空时才回退到参数 JSON 文本匹配。
 */
public record ToolRiskContext(
    String toolName, ToolEffect effect, String argsJson, List<String> targetPaths) {
  public ToolRiskContext {
    toolName = toolName == null ? "" : toolName;
    effect = effect == null ? ToolEffect.READ_ONLY : effect;
    argsJson = argsJson == null ? "{}" : argsJson;
    targetPaths =
        targetPaths == null
            ? List.of()
            : targetPaths.stream()
                .filter(path -> path != null && !path.isBlank())
                .map(ToolRiskContext::normalize)
                .toList();
  }

  /** 兼容既有三参调用：未注入目标路径时按参数文本兜底。 */
  public ToolRiskContext(String toolName, ToolEffect effect, String argsJson) {
    this(toolName, effect, argsJson, List.of());
  }

  /** 统一分隔符、去掉前导 {@code ./} 并转小写，便于按路径片段匹配。 */
  static String normalize(String path) {
    String value = path.strip().replace('\\', '/').toLowerCase(java.util.Locale.ROOT);
    while (value.startsWith("./")) value = value.substring(2);
    return value;
  }
}
