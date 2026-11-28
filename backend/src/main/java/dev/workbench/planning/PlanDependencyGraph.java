package dev.workbench.planning;

import dev.workbench.framework.workflow.plan.PlanNode;
import dev.workbench.framework.workflow.plan.PlanNodeStatus;
import dev.workbench.framework.workflow.plan.WorkflowGraph;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

/**
 * 由节点视图构建依赖图并计算就绪状态。
 *
 * <p>构造图本身即完成一次结构校验：节点标识、依赖存在性、重复依赖与环都会在此被拒绝；
 * 因此任何读取计划的地方都先经过这里，绝不把畸形依赖当成无依赖。
 */
final class PlanDependencyGraph {
  private PlanDependencyGraph() {}

  /** 构建并校验依赖图。 */
  static WorkflowGraph graph(List<PlanNode> nodes) {
    return new WorkflowGraph(
        nodes.stream()
            .map(
                node ->
                    new WorkflowGraph.Node(
                        node.id().toString(),
                        node.dependencyIds().stream().map(UUID::toString).toList()))
            .toList());
  }

  /** 待执行且所有前置节点已完成或可靠复用的节点标识，保持计划顺序。 */
  static List<String> ready(List<PlanNode> nodes) {
    var states = new LinkedHashMap<String, PlanNodeStatus>();
    for (var node : nodes) states.put(node.id().toString(), node.status());
    return graph(nodes).ready(states);
  }

  /** 就绪节点视图，供调用方在事务内认领并校验写集互斥。 */
  static List<PlanNode> readyNodes(List<PlanNode> nodes) {
    var ready = ready(nodes);
    return nodes.stream()
        .filter(node -> ready.contains(node.id().toString()))
        .toList();
  }

  /** 只做结构校验，返回值可安全忽略。 */
  static void validate(List<PlanNode> nodes) {
    graph(nodes);
  }
}
