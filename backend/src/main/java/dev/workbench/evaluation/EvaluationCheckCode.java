package dev.workbench.evaluation;

/** 固定评测的可追溯断言编码。 */
public enum EvaluationCheckCode {
  /** 实际运行终态与样本预期一致。 */
  RUN_STATUS,
  /** 计划节点数量符合正常协作样本预期。 */
  PLAN_NODE_COUNT,
  /** 正常协作样本中的计划节点均已完成。 */
  PLAN_NODES_COMPLETED,
  /** 存在成功完成的工具调用。 */
  TOOL_SUCCESS,
  /** 存在失败的工具调用记录。 */
  TOOL_FAILURE,
  /** 工具预算拒绝时没有启动工具。 */
  TOOL_NOT_STARTED,
  /** 运行记录中的工具计数为零。 */
  RUN_TOOL_COUNT_ZERO,
  /** 重复调用保护是运行停止的明确原因。 */
  REPEAT_STOP_CAUSE,
  /** 重复保护生效前仅启动允许次数的工具。 */
  REPEAT_TOOL_COUNT,
  DYNAMIC_SAVED_PLAN,
  DYNAMIC_INDEPENDENT_INSTANCES,
  DYNAMIC_MERGE_RECEIPTS,
  DYNAMIC_MODEL_CALLS,
  DYNAMIC_BUDGET_STOP,
  /** 系统检查记录状态与样本预期一致。 */
  DYNAMIC_CHECK_STATUS,
  /** 通过的检查记录了本次输入来源和结果证据。 */
  DYNAMIC_CHECK_EVIDENCE,
  /** 检查阻塞后没有启动下游 Agent、模型或工具调用。 */
  DYNAMIC_CHECK_BLOCKED
}
