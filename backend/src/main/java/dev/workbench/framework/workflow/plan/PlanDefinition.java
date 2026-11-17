package dev.workbench.framework.workflow.plan;

import dev.workbench.domain.AgentRole;
import dev.workbench.framework.agent.definition.AgentDefinitionRegistry;
import java.util.List;

/** 本期计划的不可变声明；节点仅描述预期，不能当作实际调用记录。 */
public record PlanDefinition(List<Node> nodes) {
  public PlanDefinition {
    nodes = List.copyOf(nodes);
  }

  /** 将旧角色计划转为节点身份和 Agent 定义分离的通用声明。 */
  public WorkflowDefinition workflow() {
    return new WorkflowDefinition(
        nodes.stream()
            .map(
                node ->
                    new WorkflowDefinition.Node(
                        node.role().name(),
                        AgentDefinitionRegistry.builtinReference(node.role()),
                        node.dependencies().stream().map(AgentRole::name).toList()))
            .toList());
  }

  /** 每个角色在本期只出现一次，依赖引用同一计划内的角色。 */
  public record Node(AgentRole role, List<AgentRole> dependencies) {
    public Node {
      dependencies = List.copyOf(dependencies);
    }
  }
}
