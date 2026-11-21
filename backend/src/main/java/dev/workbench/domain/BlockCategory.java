package dev.workbench.domain;

/** 兼容旧 status 的阻塞一级分类；历史运行无该字段时按停止原因推导。 */
public enum BlockCategory {
  /** 执行已完成，等待用户核对或交付确认。 */
  USER_REVIEW,
  /** 缺少输入、检查失败、产物缺失或写入结果未知。 */
  EXECUTION_BLOCKED;

  public static BlockCategory from(RunStatus status, String stopCause) {
    if (status != RunStatus.BLOCKED) return null;
    if (StopCause.CHECK_FAILED.name().equals(stopCause)
        || StopCause.OUTPUT_MISSING.name().equals(stopCause)) return EXECUTION_BLOCKED;
    return USER_REVIEW;
  }
}
