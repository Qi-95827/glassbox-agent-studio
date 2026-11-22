package dev.workbench.framework.tool;

/** 用户对一次高危工具调用的决策。 */
public enum ToolApprovalAction {
  APPROVE,
  /** 参数被用户修改；必须重新经过 Gate 5 授权交集复验后才能执行。 */
  APPROVE_WITH_EDITED_ARGS,
  REJECT
}
