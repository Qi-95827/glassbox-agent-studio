package dev.workbench.tool;

import dev.workbench.domain.ToolKind;
import java.time.Instant;
import java.util.UUID;

/** 工具成功或明确可纠正拒绝的版本化结果；未知副作用仍通过异常处理。 */
public record ToolResult<T>(
    int schemaVersion,
    UUID toolCallId,
    ToolKind toolId,
    int toolVersion,
    Status status,
    ToolEffect effect,
    Instant startedAt,
    Instant finishedAt,
    long durationMs,
    T data,
    Error error) {
  /** 拒绝是明确的调用结果，不代表任务已成功。 */
  public enum Status {
    SUCCEEDED,
    REJECTED
  }

  public enum ErrorCode {
    INVALID_ARGUMENT,
    STALE_VERSION
  }

  /** 可在修正输入后重试，不允许绕过全局预算及权限。 */
  public record Error(ErrorCode code, String message, boolean mayRetryAfterCorrection) {}
}
