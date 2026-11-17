package dev.workbench.evaluation;

/** 样本状态；中断和跳过均不能计算为通过。 */
public enum CaseStatus {
  PENDING,
  RUNNING,
  PASSED,
  FAILED,
  /** 所关联的运行已实际取消。 */
  CANCELLED,
  INTERRUPTED,
  SKIPPED
}
