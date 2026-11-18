package dev.workbench.framework.workflow.plan;

/**
 * 计划节点由哪类受信执行器处理。
 *
 * <p>历史记录没有节点类型列，迁移后按 {@link #AGENT} 读取；检查节点不创建 Agent 实例，也不能使用 Agent 定义、模型或工具权限。
 */
public enum PlanNodeKind {
  /** 由 Agent 执行器装配独立实例并调用模型。 */
  AGENT,
  /** 由受信系统检查执行器确定性处理，不创建 Agent 实例。 */
  SYSTEM_CHECK
}
