package dev.workbench.domain;

/** Agent、模型或工具的单次调用状态，不包含运行队列状态。 */
public enum InvocationStatus {
  /** 调用正在执行。 */
  RUNNING,
  /** 调用正常返回。 */
  COMPLETED,
  /** 调用失败。 */
  FAILED,
  /** 调用因用户取消而结束。 */
  CANCELLED,
  /** 调用因运行资源超限而结束。 */
  LIMITED,
  /** 进程中断，调用结果可能未知。 */
  INTERRUPTED;

  /** 运行停止时，将运行状态转换为相应的调用终态。 */
  public static InvocationStatus fromRunStatus(RunStatus status) {
    return switch (status) {
      case COMPLETED -> COMPLETED;
      case FAILED -> FAILED;
      case CANCELLED -> CANCELLED;
      case LIMITED -> LIMITED;
      case INTERRUPTED -> INTERRUPTED;
      default -> throw new IllegalArgumentException("运行尚未结束，无法转换为调用终态。");
    };
  }
}
