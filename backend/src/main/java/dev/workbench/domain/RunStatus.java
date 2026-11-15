package dev.workbench.domain;

/** 一次任务运行的生命周期状态。 */
public enum RunStatus {
  /** 已排队，尚未开始执行。 */
  PENDING,
  /** 正在执行或等待当前调用完成。 */
  RUNNING,
  /** 已在合作检查点暂停；进行中的外部请求可能仍在结束。 */
  PAUSED,
  /** 已完成本次任务并保存结果。 */
  COMPLETED,
  /** 执行异常，未达到完成条件。 */
  FAILED,
  /** 用户请求取消且执行器已处理。 */
  CANCELLED,
  /** 触及轮次、工具次数或时间上限。 */
  LIMITED,
  /** 缺少复现、验证或执行条件，等待用户处理。 */
  BLOCKED,
  /** 进程中断，可能需要检查点恢复。 */
  INTERRUPTED;

  /** 是否仍允许接收取消请求。 */
  public boolean canCancel() {
    return this == PENDING || this == RUNNING || this == PAUSED;
  }
}
