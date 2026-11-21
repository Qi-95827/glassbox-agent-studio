package dev.workbench.framework.workflow.plan;

import java.util.List;

/** 节点最终使用的定义与可展示的选择依据；私有组件版本固定在这里。 */
public record AgentBinding(
    String definitionId,
    int definitionVersion,
    String source,
    String reason,
    String name,
    String specSha256,
    String modelStrategy,
    String modelMode,
    List<BindingComponent> skills,
    List<BindingComponent> tools) {
  public AgentBinding {
    skills = slots(skills);
    tools = slots(tools);
  }

  private static List<BindingComponent> slots(List<BindingComponent> value) {
    return value == null ? List.of() : List.copyOf(value);
  }
}
