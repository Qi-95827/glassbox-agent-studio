package dev.workbench.domain;

/** 兼容旧 status 的阻塞一级分类；历史运行无停止码时保持未知。 */
public enum BlockCategory {
  /** 执行已完成，等待用户核对或交付确认。 */
  USER_REVIEW,
  /** 缺少输入、检查失败、产物缺失或写入结果未知。 */
  EXECUTION_BLOCKED,
  /** 历史记录没有停止码，不能推断为任何一类。 */
  UNKNOWN;

  public static BlockCategory from(RunStatus status, String stopCause) {
    if (status != RunStatus.BLOCKED) return null;
    if (stopCause == null || stopCause.isBlank()) return UNKNOWN;
    return switch (stopCause) {
      case "CHECK_FAILED", "OUTPUT_MISSING", "VALIDATION_MISSING" -> EXECUTION_BLOCKED;
      case "USER_REVIEW" -> USER_REVIEW;
      default -> USER_REVIEW;
    };
  }
}
