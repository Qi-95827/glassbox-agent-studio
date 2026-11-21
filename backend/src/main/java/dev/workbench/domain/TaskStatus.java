package dev.workbench.domain;

/**
 * 任务（Task）业务生命周期状态，独立于单次 Agent 运行（RunStatus）的不可变审计快照。
 *
 * <p>Run 保留 AI 执行结束那一刻的真实状态（例如写完文件后停在 BLOCKED / USER_REVIEW）； Task 则反映整个业务交付是否已闭环（例如人工审查并完成本地提交或合并后进入
 * DELIVERED）。
 */
public enum TaskStatus {
  /** 任务已创建、排队或正在执行中。 */
  IN_PROGRESS,
  /** AI 已完成写入或阶段产出，等待用户审查与交付。 */
  AWAITING_REVIEW,
  /** 任务关联的代码变更已通过人工审查并完成 Git 提交或合并交付。 */
  DELIVERED,
  /** 无需代码提交的纯分析/文本任务已正常完成。 */
  COMPLETED,
  /** 因缺少输入、检查失败或产物缺失而阻塞。 */
  BLOCKED,
  /** 运行失败、超限或进程中断。 */
  FAILED,
  /** 用户主动取消。 */
  CANCELLED;

  /** 按最新一次 Run 状态与工作区交付事实确定性推导任务业务状态。 */
  public static TaskStatus derive(
      String runStatusText, boolean hasWorkspace, boolean workspaceDelivered) {
    if (runStatusText == null || runStatusText.isBlank()) return IN_PROGRESS;
    RunStatus runStatus;
    try {
      runStatus = RunStatus.valueOf(runStatusText);
    } catch (IllegalArgumentException e) {
      return IN_PROGRESS;
    }
    return switch (runStatus) {
      case PENDING, RUNNING, PAUSED -> IN_PROGRESS;
      case COMPLETED -> workspaceDelivered ? DELIVERED : COMPLETED;
      case BLOCKED ->
          workspaceDelivered ? DELIVERED : (hasWorkspace ? AWAITING_REVIEW : BLOCKED);
      case CANCELLED -> CANCELLED;
      case FAILED, LIMITED, INTERRUPTED -> FAILED;
    };
  }
}
