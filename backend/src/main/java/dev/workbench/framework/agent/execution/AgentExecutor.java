package dev.workbench.framework.agent.execution;

/** 协调器调用 Agent 的边界，具体推理框架由适配器实现。 */
public interface AgentExecutor {
  /** 执行一次独立角色调用；状态和预算由请求中的运行控制接管。 */
  String execute(AgentExecutionRequest request) throws InterruptedException;
}
