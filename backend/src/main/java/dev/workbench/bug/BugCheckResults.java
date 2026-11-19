package dev.workbench.bug;

import dev.workbench.framework.workflow.check.SystemCheckResult;
import dev.workbench.framework.workflow.check.SystemCheckStatus;

/** 将既有容器检测结果映射为系统检查结论，保留归属与代码版本信息。 */
final class BugCheckResults {
  private static final int OUTPUT_EXCERPT = 1200;

  private BugCheckResults() {}

  static SystemCheckResult reproduction(BugService.Check check) {
    return switch (check.status()) {
      case FAILURES_OBSERVED ->
          new SystemCheckResult(
              SystemCheckStatus.PASSED,
              summary(check, "观察到测试失败，可以进入定位与修复。"),
              evidence(check),
              check.id());
      case NOT_REPRODUCED ->
          new SystemCheckResult(
              SystemCheckStatus.FAILED,
              summary(check, "未观察到测试失败，不能进入定位或修改。"),
              evidence(check),
              check.id());
      case TARGET_MISMATCH ->
          SystemCheckResult.failed(
              summary(check, "测试存在失败，但未出现模拟 Bug 的目标现象；不能作为该 Bug 的复现证据。"),
              evidence(check),
              check.id(),
              "REPRODUCTION_TARGET_MISMATCH");
      case TARGET_NOT_EXECUTED ->
          SystemCheckResult.failed(
              summary(check, "没有找到并执行目标测试用例；相似名称或其他失败不能替代目标用例。"),
              evidence(check),
              check.id(),
              "REPRODUCTION_TARGET_NOT_EXECUTED");
      case TARGET_SKIPPED ->
          SystemCheckResult.failed(
              summary(check, "目标测试用例被跳过，未实际执行，不能作为该 Bug 的复现证据。"),
              evidence(check),
              check.id(),
              "REPRODUCTION_TARGET_SKIPPED");
      case NEEDS_INFO ->
          SystemCheckResult.blocked(
              summary(check, "缺少可执行测试或验证条件，请补充信息。"),
              evidence(check),
              check.id(),
              "REPRODUCTION_MISSING_INFO");
      case ERROR ->
          new SystemCheckResult(
              SystemCheckStatus.BLOCKED,
              summary(check, "Docker 或容器环境不可用，复现检查未完成。"),
              evidence(check),
              check.id());
      case TIMEOUT ->
          new SystemCheckResult(
              SystemCheckStatus.BLOCKED,
              summary(check, "复现检查超时，未完成验证。"),
              evidence(check),
              check.id());
      case INTERRUPTED ->
          new SystemCheckResult(
              SystemCheckStatus.BLOCKED,
              summary(check, "复现检查被中断，容器结果未知。"),
              evidence(check),
              check.id());
      case STALE ->
          new SystemCheckResult(
              SystemCheckStatus.BLOCKED,
              summary(check, "工作区在检查期间发生变化，结果不对应当前代码版本。"),
              evidence(check),
              check.id());
      case PASSED, FAILED ->
          new SystemCheckResult(
              SystemCheckStatus.BLOCKED,
              summary(check, "复现检查返回了不适用的状态，不能作为复现证据。"),
              evidence(check),
              check.id());
    };
  }

  static SystemCheckResult regression(BugService.Check check) {
    return switch (check.status()) {
      case PASSED ->
          new SystemCheckResult(
              SystemCheckStatus.PASSED,
              summary(check, "回归测试通过，仍需用户核对差异与原问题。"),
              evidence(check),
              check.id());
      case FAILED, FAILURES_OBSERVED ->
          new SystemCheckResult(
              SystemCheckStatus.FAILED,
              summary(check, "回归测试仍失败或未通过，不得报告 Bug 已修复。"),
              evidence(check),
              check.id());
      case TARGET_NOT_EXECUTED ->
          SystemCheckResult.failed(
              summary(check, "目标测试用例未实际执行，不能报告 Bug 已修复。"),
              evidence(check),
              check.id(),
              "REGRESSION_TARGET_NOT_EXECUTED");
      case TARGET_SKIPPED ->
          SystemCheckResult.failed(
              summary(check, "目标测试用例被跳过，不能报告 Bug 已修复。"),
              evidence(check),
              check.id(),
              "REGRESSION_TARGET_SKIPPED");
      case NEEDS_INFO ->
          SystemCheckResult.blocked(
              summary(check, "回归检查缺少可执行测试或验证条件。"),
              evidence(check),
              check.id(),
              "REGRESSION_MISSING_INFO");
      case ERROR ->
          new SystemCheckResult(
              SystemCheckStatus.BLOCKED,
              summary(check, "Docker 或容器环境不可用，回归检查未完成。"),
              evidence(check),
              check.id());
      case TIMEOUT ->
          new SystemCheckResult(
              SystemCheckStatus.BLOCKED,
              summary(check, "回归检查超时，未完成验证。"),
              evidence(check),
              check.id());
      case INTERRUPTED ->
          new SystemCheckResult(
              SystemCheckStatus.BLOCKED,
              summary(check, "回归检查被中断，容器结果未知。"),
              evidence(check),
              check.id());
      case STALE ->
          new SystemCheckResult(
              SystemCheckStatus.BLOCKED,
              summary(check, "工作区在检查期间发生变化，结果不对应当前代码版本。"),
              evidence(check),
              check.id());
      case NOT_REPRODUCED, TARGET_MISMATCH ->
          new SystemCheckResult(
              SystemCheckStatus.BLOCKED,
              summary(check, "回归检查返回了不适用的状态，不能作为通过证据。"),
              evidence(check),
              check.id());
    };
  }

  private static String summary(BugService.Check check, String message) {
    String fingerprint =
        check.workspaceFingerprint() == null ? "未记录" : check.workspaceFingerprint();
    return "检测记录 " + check.id() + "，状态 " + check.status() + "，代码版本 " + fingerprint + "。" + message;
  }

  private static String evidence(BugService.Check check) {
    String output = check.output() == null ? "" : check.output().strip();
    if (output.length() > OUTPUT_EXCERPT)
      output = "…" + output.substring(output.length() - OUTPUT_EXCERPT);
    return "类型 "
        + check.kind()
        + "；运行 "
        + check.runId()
        + "；Bug "
        + check.bugId()
        + "；输出摘要：\n"
        + output;
  }
}
