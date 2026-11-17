package dev.workbench.framework.agent.definition;

/** 受控 Agent 定义的开放标识与不可变版本，不是实际运行实例。 */
public record AgentDefinitionRef(String id, int version) {
  public AgentDefinitionRef {
    if (id == null || !id.matches("[a-z][a-z0-9.-]{0,63}") || version < 1) {
      throw new IllegalArgumentException("Agent 定义标识或版本无效。");
    }
  }
}
