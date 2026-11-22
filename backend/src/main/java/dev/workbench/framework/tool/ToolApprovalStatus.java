package dev.workbench.framework.tool;

/** 审批单状态；只有 PENDING 可被用户决策。 */
public enum ToolApprovalStatus {
  PENDING,
  APPROVED,
  APPROVED_WITH_EDITED_ARGS,
  REJECTED,
  EXPIRED
}
