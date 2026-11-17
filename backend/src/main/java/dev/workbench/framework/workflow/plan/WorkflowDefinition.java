package dev.workbench.framework.workflow.plan;

import dev.workbench.framework.agent.definition.AgentDefinitionRef;
import java.util.List;
import java.util.Objects;

/** 工作流的不可变声明：节点身份、Agent 定义和依赖分别表示不同概念。 */
public record WorkflowDefinition(List<Node> nodes) {
  public WorkflowDefinition {
    if (nodes == null) throw new IllegalArgumentException("工作流节点不能为空。");
    nodes = List.copyOf(nodes);
    // 复用图校验，拒绝缺失依赖、重复节点和循环依赖。
    new WorkflowGraph(
        nodes.stream()
            .map(node -> new WorkflowGraph.Node(node.id(), node.dependencies()))
            .toList());
  }

  /** 多个节点可以引用同一 Agent 定义，但每个节点必须有独立标识。 */
  public record Node(String id, AgentDefinitionRef agent, List<String> dependencies) {
    public Node {
      Objects.requireNonNull(agent, "Agent 定义引用不能为空");
      var checked = new WorkflowGraph.Node(id, dependencies);
      dependencies = checked.dependencies();
    }
  }
}
