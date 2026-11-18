package dev.workbench.domain;

/** 受控停止及已知失败的机器可读原因；历史记录可能没有该字段。 */
public enum StopCause {
  USER_CANCELLED(RunStatus.CANCELLED),
  INTERRUPTED(RunStatus.INTERRUPTED),
  TIME_LIMIT(RunStatus.LIMITED),
  MODEL_ROUND_LIMIT(RunStatus.LIMITED),
  AGENT_MODEL_LIMIT(RunStatus.LIMITED),
  ROLE_MODEL_LIMIT(RunStatus.LIMITED),
  NODE_MODEL_LIMIT(RunStatus.LIMITED),
  MODEL_RATE_LIMIT(RunStatus.FAILED),
  TOKEN_USAGE_UNKNOWN(RunStatus.LIMITED),
  TOKEN_LIMIT(RunStatus.LIMITED),
  TOOL_CALL_LIMIT(RunStatus.LIMITED),
  AGENT_TOOL_LIMIT(RunStatus.LIMITED),
  ROLE_TOOL_LIMIT(RunStatus.LIMITED),
  NODE_TOOL_LIMIT(RunStatus.LIMITED),
  PER_TOOL_LIMIT(RunStatus.LIMITED),
  SPECIFIC_TOOL_LIMIT(RunStatus.LIMITED),
  REPEATED_TOOL_LIMIT(RunStatus.LIMITED),
  TOOL_EFFECT_UNKNOWN(RunStatus.FAILED),
  /** 系统检查未通过或无法判定，运行保留已知记录并等待用户处理。 */
  CHECK_FAILED(RunStatus.BLOCKED),
  RECOVERY_PRECONDITION_FAILED(RunStatus.FAILED),
  UNCLASSIFIED_FAILURE(RunStatus.FAILED);

  private final RunStatus status;

  StopCause(RunStatus status) {
    this.status = status;
  }

  public RunStatus status() {
    return status;
  }
}
