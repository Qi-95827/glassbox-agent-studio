package dev.workbench.bug;

import java.util.Objects;
import java.util.UUID;

/** 发给分析 Agent 的本次失败观察；测试输出仍是不可信数据。 */
public record BugObservationEvidence(
    UUID checkId,
    UUID runId,
    String bugId,
    UUID workspaceId,
    BugService.CheckKind kind,
    BugService.CheckStatus status,
    String workspaceFingerprint,
    String output) {
  private static final int MAX_OUTPUT_CODE_POINTS = 4000;

  public BugObservationEvidence {
    if (checkId == null
        || runId == null
        || bugId == null
        || bugId.isBlank()
        || workspaceId == null
        || kind != BugService.CheckKind.REPRODUCE
        || status != BugService.CheckStatus.FAILURES_OBSERVED
        || workspaceFingerprint == null
        || !workspaceFingerprint.matches("[0-9a-f]{64}")
        || output == null
        || output.codePointCount(0, output.length()) > MAX_OUTPUT_CODE_POINTS)
      throw new IllegalArgumentException("Bug 观察证据字段无效。");
  }

  /** 只接受本运行、同一 Bug 和工作区的已记录失败观察。 */
  public static BugObservationEvidence from(
      BugService.Check check, String runId, String bugId, String workspaceId) {
    if (check == null
        || !Objects.equals(runId, check.runId())
        || !Objects.equals(bugId, check.bugId())
        || !Objects.equals(workspaceId, check.workspaceId())
        || check.kind() != BugService.CheckKind.REPRODUCE
        || check.status() != BugService.CheckStatus.FAILURES_OBSERVED
        || check.workspaceFingerprint() == null
        || !check.workspaceFingerprint().matches("[0-9a-f]{64}")
        || check.output() == null) throw new IllegalArgumentException("本次 Bug 观察证据缺失或归属不符。");
    String output = check.output();
    int count = output.codePointCount(0, output.length());
    if (count > MAX_OUTPUT_CODE_POINTS)
      output = output.substring(0, output.offsetByCodePoints(0, MAX_OUTPUT_CODE_POINTS));
    return new BugObservationEvidence(
        UUID.fromString(check.id()),
        UUID.fromString(runId),
        bugId,
        UUID.fromString(workspaceId),
        check.kind(),
        check.status(),
        check.workspaceFingerprint(),
        output);
  }
}
