package dev.workbench.framework.workflow.plan;

/** 一次运行中固定协作步骤的状态；跳过不代表执行成功。 */
public enum PlanNodeStatus {
  /** 等待前置步骤完成，尚未创建 Agent 调用。 */
  PENDING,
  /** Agent 已启动，调用尚未结束。 */
  RUNNING,
  /** Agent 正常返回，具体结论仍需结合证据判断。 */
  COMPLETED,
  /** 从已校验的历史运行复用结果，没有在本运行新建该角色调用。 */
  REUSED,
  /** 调用失败。 */
  FAILED,
  /** 调用已响应取消请求。 */
  CANCELLED,
  /** 调用因预算或时间限制而终止。 */
  LIMITED,
  /** 调用中断，结果可能未知。 */
  INTERRUPTED,
  /** 运行结束前未启动该步骤。 */
  SKIPPED
}
