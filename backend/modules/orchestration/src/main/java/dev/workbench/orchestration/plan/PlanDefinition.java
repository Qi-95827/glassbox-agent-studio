package dev.workbench.orchestration.plan;

import dev.workbench.domain.AgentRole;
import java.util.List;

/** 本期计划的不可变声明；节点仅描述预期，不能当作实际调用记录。 */
public record PlanDefinition(List<Node> nodes) {
  public PlanDefinition {
    nodes = List.copyOf(nodes);
  }

  /** 每个角色在本期只出现一次，依赖引用同一计划内的角色。 */
  public record Node(AgentRole role, List<AgentRole> dependencies) {
    public Node {
      dependencies = List.copyOf(dependencies);
    }
  }
}
