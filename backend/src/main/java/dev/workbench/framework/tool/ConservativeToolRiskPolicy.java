package dev.workbench.framework.tool;

import java.util.List;

/**
 * 零外部依赖的保守风险策略。
 *
 * <p>只读、搜索与模拟检查自动放行；普通授权写入按策略放行；敏感配置路径、依赖清单、CI 配置与 {@code mcp::} 外部写操作强制 HIGH，必须进入 Gate 5.5 审批。
 *
 * <p>判定顺序：调用方注入了明确目标路径时**只看路径**（避免代码正文里恰好出现 {@code security} 之类 字样造成误拦截）；没有目标路径时才回退到参数 JSON 文本匹配。
 */
public final class ConservativeToolRiskPolicy implements ToolRiskPolicy {
  private static final List<String> SENSITIVE_MARKERS =
      List.of(
          "pom.xml",
          "build.gradle",
          "settings.gradle",
          "package.json",
          "package-lock.json",
          "pnpm-lock.yaml",
          "yarn.lock",
          "dockerfile",
          "docker-compose",
          ".env",
          "application.properties",
          "application.yml",
          "application.yaml",
          "security",
          ".github/workflows",
          "gradle.properties");

  @Override
  public ToolRiskAssessment assess(ToolRiskContext context) {
    if (context.effect() == ToolEffect.READ_ONLY)
      return new ToolRiskAssessment(ToolRiskLevel.LOW, "只读调用，不产生外部副作用");
    if (!context.targetPaths().isEmpty()) {
      for (String path : context.targetPaths())
        for (String marker : SENSITIVE_MARKERS)
          if (path.contains(marker))
            return new ToolRiskAssessment(
                ToolRiskLevel.HIGH, "命中敏感配置或依赖路径：" + marker + "，需要用户显式批准");
      if (context.toolName().startsWith("mcp::"))
        return new ToolRiskAssessment(ToolRiskLevel.HIGH, "外部 MCP 写操作可能产生网络副作用，需要用户显式批准");
      return new ToolRiskAssessment(ToolRiskLevel.MEDIUM, "已授权范围内的常规写入");
    }
    String lower = context.argsJson().toLowerCase(java.util.Locale.ROOT);
    for (String marker : SENSITIVE_MARKERS)
      if (lower.contains(marker))
        return new ToolRiskAssessment(ToolRiskLevel.HIGH, "命中敏感配置或依赖路径：" + marker + "，需要用户显式批准");
    if (context.toolName().startsWith("mcp::"))
      return new ToolRiskAssessment(ToolRiskLevel.HIGH, "外部 MCP 写操作可能产生网络副作用，需要用户显式批准");
    return new ToolRiskAssessment(ToolRiskLevel.MEDIUM, "已授权范围内的常规写入");
  }
}
