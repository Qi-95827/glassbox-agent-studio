package dev.workbench.framework.workflow.plan;

import dev.workbench.framework.agent.definition.AgentDefinitionRef;
import dev.workbench.framework.workflow.check.SystemCheckRef;
import java.util.List;

/** 工作流的不可变声明：节点身份、执行器引用和依赖分别表示不同概念。 */
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

  /** 多个节点可以引用同一 Agent 定义或检查，但每个节点必须有独立标识且只选择一种执行器。 */
  public record Node(
      String id, AgentDefinitionRef agent, SystemCheckRef check, List<String> dependencies) {
    /** 兼容只使用 Agent 执行器的历史计划声明。 */
    public Node(String id, AgentDefinitionRef agent, List<String> dependencies) {
      this(id, agent, null, dependencies);
    }

    public Node {
      if ((agent == null) == (check == null))
        throw new IllegalArgumentException("工作流节点必须且只能引用一个 Agent 定义或系统检查。");
      var checked = new WorkflowGraph.Node(id, dependencies);
      dependencies = checked.dependencies();
    }
  }
}
