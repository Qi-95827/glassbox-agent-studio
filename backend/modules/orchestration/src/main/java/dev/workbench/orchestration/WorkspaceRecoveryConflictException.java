package dev.workbench.orchestration;

/** 恢复开始前无法确认工作区与检查点一致。 */
final class WorkspaceRecoveryConflictException extends IllegalStateException {
  WorkspaceRecoveryConflictException(String message) {
    super(message);
  }
}
