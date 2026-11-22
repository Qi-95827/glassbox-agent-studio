package dev.workbench.framework.tool;

/** Gate 5.5 返回给工具网关的决策；editedArgsJson 仅在改参放行时非空。 */
public record ToolApprovalDecision(
    ToolApprovalAction action, String editedArgsJson, String reason) {
  public ToolApprovalDecision {
    action = action == null ? ToolApprovalAction.REJECT : action;
    editedArgsJson =
        editedArgsJson == null || editedArgsJson.isBlank() ? null : editedArgsJson.strip();
    reason = reason == null ? null : reason.strip();
    if (action == ToolApprovalAction.APPROVE_WITH_EDITED_ARGS && editedArgsJson == null)
      throw new IllegalArgumentException("改参放行必须提供修改后的参数 JSON。");
  }

  public static ToolApprovalDecision approve() {
    return new ToolApprovalDecision(ToolApprovalAction.APPROVE, null, null);
  }

  public static ToolApprovalDecision reject(String reason) {
    return new ToolApprovalDecision(ToolApprovalAction.REJECT, null, reason);
  }

  public boolean rejected() {
    return action == ToolApprovalAction.REJECT;
  }

  public boolean edited() {
    return action == ToolApprovalAction.APPROVE_WITH_EDITED_ARGS;
  }
}
