package dev.workbench.workspace;

/** 文件版本不匹配且目标写入尚未执行，可重新读取后人工或模型修正参数。 */
public class WorkspaceVersionConflict extends IllegalStateException {
  public WorkspaceVersionConflict(String message) {
    super(message);
  }
}
