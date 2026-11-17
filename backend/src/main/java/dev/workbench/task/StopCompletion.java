package dev.workbench.task;

import dev.workbench.domain.HandoffSource;
import dev.workbench.domain.HandoffStatus;
import dev.workbench.domain.StopCause;
import dev.workbench.orchestration.Handoff;
import java.util.Objects;
import java.util.UUID;

/** 受控停止时必须一致保存的运行、Agent 和系统交接。 */
public record StopCompletion(
    UUID runId,
    UUID agentId,
    StopCause cause,
    String reason,
    int rounds,
    int tools,
    long elapsedMs,
    Handoff systemHandoff) {
  public StopCompletion {
    Objects.requireNonNull(runId, "运行 ID 不能为空");
    Objects.requireNonNull(cause, "停止原因不能为空");
    if (reason == null || reason.isBlank() || rounds < 0 || tools < 0 || elapsedMs < 0)
      throw new IllegalArgumentException("停止说明或预算无效。");
    if ((agentId == null) != (systemHandoff == null))
      throw new IllegalArgumentException("Agent 与系统交接必须同时提供或同时为空。");
    if (systemHandoff != null
        && (!runId.toString().equals(systemHandoff.runId())
            || !agentId.toString().equals(systemHandoff.agentId())
            || systemHandoff.source() != HandoffSource.SYSTEM
            || systemHandoff.status() != HandoffStatus.valueOf(cause.status().name())
            || !reason.equals(systemHandoff.summary())))
      throw new IllegalArgumentException("系统交接与受控停止不匹配。");
  }
}
