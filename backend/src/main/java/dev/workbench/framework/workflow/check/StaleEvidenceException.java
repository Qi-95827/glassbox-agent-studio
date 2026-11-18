package dev.workbench.framework.workflow.check;

/**
 * 已保存的系统检查证据在消费时不再对应当前工作区或归属。
 *
 * <p>历史检查记录保持原状态；调度器把该异常转换为 `BLOCKED`，不启动依赖该证据的节点。
 */
public class StaleEvidenceException extends IllegalStateException {
  public StaleEvidenceException(String message) {
    super(message);
  }
}
