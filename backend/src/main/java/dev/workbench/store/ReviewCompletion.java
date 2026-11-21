package dev.workbench.store;

import dev.workbench.domain.RunStatus;
import dev.workbench.domain.Handoff;
import java.util.Objects;
import java.util.UUID;

/** 审查完成后需要一起保存的本地终态数据。 */
public record ReviewCompletion(
    UUID runId,
    UUID agentId,
    String review,
    Handoff resultHandoff,
    RunStatus status,
    dev.workbench.domain.StopCause stopCause,
    String reason,
    String result,
    int rounds,
    int tools,
    long elapsedMs) {
  public ReviewCompletion {
    Objects.requireNonNull(runId, "运行 ID 不能为空");
    Objects.requireNonNull(agentId, "Agent ID 不能为空");
    Objects.requireNonNull(review, "审查内容不能为空");
    Objects.requireNonNull(resultHandoff, "审查交接不能为空");
    if (status != RunStatus.COMPLETED && status != RunStatus.BLOCKED)
      throw new IllegalArgumentException("审查终态只能是已完成或待处理。");
    if ((status == RunStatus.BLOCKED
            && (stopCause == null || stopCause.status() != RunStatus.BLOCKED))
        || (status == RunStatus.COMPLETED && stopCause != null))
      throw new IllegalArgumentException("审查终态与停止原因不一致。");
    if ((status == RunStatus.BLOCKED && (reason == null || reason.isBlank()))
        || (status == RunStatus.COMPLETED && reason != null)
        || result == null
        || result.isBlank()
        || rounds < 0
        || tools < 0
        || elapsedMs < 0) throw new IllegalArgumentException("运行终态内容或预算无效。");
    if (!runId.toString().equals(resultHandoff.runId())
        || !agentId.toString().equals(resultHandoff.agentId()))
      throw new IllegalArgumentException("审查交接与运行或 Agent 不匹配。");
  }
}
