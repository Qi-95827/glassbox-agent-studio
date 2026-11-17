package dev.workbench.framework.agent.definition;

/** 由服务端受信代码贡献一份定义；不接受模型或用户直接注册可执行代码。 */
public interface AgentDefinitionProvider {
  AgentDefinition definition();
}
