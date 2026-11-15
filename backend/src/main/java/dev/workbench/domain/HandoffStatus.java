package dev.workbench.domain;

/** 交接内容本身的状态，不等同于运行状态。 */
public enum HandoffStatus {
  ASSIGNED,
  COMPLETED,
  FAILED,
  CANCELLED,
  LIMITED,
  INTERRUPTED
}
