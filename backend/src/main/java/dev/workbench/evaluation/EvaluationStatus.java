package dev.workbench.evaluation;

/** 评测批次状态；完成不表示所有样本通过。 */
public enum EvaluationStatus {
  RUNNING,
  /** 已收到取消请求，正在等待在途运行结束。 */
  CANCELLING,
  /** 不再调度后续样本，保留各样本实际结果。 */
  CANCELLED,
  COMPLETED,
  FAILED,
  INTERRUPTED
}
