package dev.workbench.control;

import dev.workbench.domain.RunStatus;
import dev.workbench.domain.StopCause;

/** 运行控制触发的受控停止，不等同于系统异常。 */
public final class StopExecution extends RuntimeException {
  private final StopCause cause;

  public StopExecution(StopCause cause, String message) {
    super(message);
    this.cause = java.util.Objects.requireNonNull(cause);
  }

  public RunStatus status() {
    return cause.status();
  }

  public StopCause stopCause() {
    return cause;
  }
}
