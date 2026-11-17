package dev.workbench.framework.tool;

/** 写操作发生异常，不能确认其副作用是否已经发生。 */
public final class UncertainToolEffectException extends IllegalStateException {
  public UncertainToolEffectException(Throwable cause) {
    super("工具写操作结果未知，需要先检查实际状态。", cause);
  }
}
