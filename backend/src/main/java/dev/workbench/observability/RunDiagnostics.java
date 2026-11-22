package dev.workbench.observability;

import java.time.Instant;
import java.util.List;

/**
 * 单次运行的自动故障诊断结果。
 *
 * <p>服务端基于持久化证据给出健康等级、一句话核心定位与固定五步排查向导；每一步都携带可一键直达的
 * 前端路由，路由中的 glassbox 参数会打开对应节点的宽屏透视 Tab。
 */
public record RunDiagnostics(
    String runId,
    String taskId,
    String taskTitle,
    String workspaceId,
    String runStatus,
    String taskStatus,
    String healthLevel,
    String primaryIssue,
    List<TroubleshootingStep> troubleshootingSteps,
    Instant generatedAt) {

  /** 单步排查结论；stepOrder 始终为 1..5 的标准排查顺序。 */
  public record TroubleshootingStep(
      int stepOrder,
      String title,
      String status,
      String finding,
      String actionLabel,
      String targetRoute) {}

  public static final String HEALTHY = "HEALTHY";
  public static final String WARNING = "WARNING";
  public static final String CRITICAL = "CRITICAL";

  public static final String ISSUE_FOUND = "ISSUE_FOUND";
  public static final String CHECK_RECOMMENDED = "CHECK_RECOMMENDED";
  public static final String LOOKS_OK = "LOOKS_OK";
}
