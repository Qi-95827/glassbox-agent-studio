package dev.workbench.domain;

/** 由执行系统记录的事件类型；编码同时供现有前端使用。 */
public enum EventType {
  /** 任务已创建。 */
  TASK_CREATED,
  /** 用户确认了已保存的计划版本。 */
  PLAN_APPROVED,
  /** 排队运行开始执行。 */
  RUN_STARTED,
  /** 协调者已委派子任务。 */
  DELEGATED,
  /** Agent 实例已开始。 */
  AGENT_STARTED,
  /** 本次尝试已结束，节点将在退避后重新认领。 */
  NODE_RETRY_SCHEDULED,
  /** 模型调用已发起。 */
  MODEL_STARTED,
  /** 模型调用已返回。 */
  MODEL_FINISHED,
  /** 工具调用已发起。 */
  TOOL_STARTED,
  /** 工具调用已返回。 */
  TOOL_FINISHED,
  /** 已调用工具但参数或版本校验拒绝，未作为成功证据。 */
  TOOL_REJECTED,
  /** Agent 已交接结果。 */
  HANDOFF,
  /** 接收交接的 Agent 启动记录已保存，不证明模型收到输入。 */
  HANDOFF_ACCEPTED,
  /** 系统检查节点已认领并开始确定性检查。 */
  CHECK_STARTED,
  /** 系统检查已结算，结果写入检查记录。 */
  CHECK_FINISHED,
  /** 内部检查点已保存。 */
  CHECKPOINT_SAVED,
  /** 分析后恢复检查点不满足保存条件；不表示运行失败。 */
  CHECKPOINT_SKIPPED,
  /** 运行已正常完成。 */
  RUN_COMPLETED,
  /** 用户已请求取消，尚不等于取消完成。 */
  CANCEL_REQUESTED,
  /** 用户请求在下一次检查时暂停。 */
  PAUSE_REQUESTED,
  /** 执行已在协作检查点暂停。 */
  PAUSED,
  /** 用户请求继续暂停的运行。 */
  RESUMED,
  /** 执行器已处理取消请求。 */
  CANCELLED,
  /** 运行发生异常并终止。 */
  FAILED,
  /** 运行达到资源限制。 */
  LIMITED,
  /** 运行中断，结果可能未知。 */
  INTERRUPTED,
  /** 因缺少必要信息被阻塞，已保存待补充事项。 */
  SUPPLEMENT_REQUESTED,
  /** 用户提交的补充通过全部校验并已应用到任务。 */
  SUPPLEMENT_APPLIED,
  /** 用户提交的补充被拒绝，运行保持阻塞等待正确提交。 */
  SUPPLEMENT_REJECTED,
  /** 补充接受后运行从安全边界重新排队执行。 */
  CONTINUED_AFTER_SUPPLEMENT,
  /** 从历史检查点创建了关联的新运行。 */
  RECOVERY_STARTED,
  /** 从历史运行的某个节点分叉出一条新的分支运行；源运行证据不变。 */
  FORK_STARTED,
  /** 同一任务收到用户调改意见后开启下一轮运行；源运行证据不变。 */
  RUN_ITERATION_STARTED,
  /** 本次任务装配用户确认的项目背景，不记录正文。 */
  MEMORY_LOADED,
  /** 开始受控测试。 */
  TEST_STARTED,
  /** 测试完成，结果保存在 Bug 检测记录中。 */
  TEST_FINISHED,
  /** 缺少继续执行的条件。 */
  BLOCKED,
  /** 用户已人工接手暂停的工作区。 */
  HANDOVER_TAKEN,
  /** 用户已交还工作区；代码变化时旧测试与审批失效。 */
  HANDOVER_RETURNED,
  /** 审查节点要求修订，目标节点已重新排队并注入结构化批评反馈。 */
  REVISION_TRIGGERED,
  /** 反思次数达到上限，自动循环停止并转人工审核。 */
  WAITING_HUMAN_REVIEW,
  /** 四级上下文压缩已执行，载荷包含压缩等级与节省比例。 */
  CONTEXT_COMPACTED,
  /** 高危工具已创建审批单并挂起节点。 */
  TOOL_APPROVAL_REQUESTED,
  /** 用户已完成工具审批决策。 */
  TOOL_APPROVAL_DECIDED;

  /** 受控停止状态对应的事件；其他状态应使用各自的事件。 */
  public static EventType fromStopStatus(RunStatus status) {
    return switch (status) {
      case CANCELLED -> CANCELLED;
      case LIMITED -> LIMITED;
      default -> throw new IllegalArgumentException("该运行状态不是受控停止事件。");
    };
  }
}
