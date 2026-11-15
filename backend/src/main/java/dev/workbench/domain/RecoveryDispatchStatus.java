package dev.workbench.domain;

/** 仅明确未启动任务的拒绝可以解除恢复防重；UNKNOWN 必须保守处理。 */
public enum RecoveryDispatchStatus {
  UNKNOWN,
  REJECTED,
  PRECONDITION_FAILED
}
