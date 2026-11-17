package dev.workbench.framework.workflow.plan;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 与角色解耦的有向任务图；只计算就绪条件，不负责启动 Agent 或持久化。 */
public final class WorkflowGraph {
  private static final int MAX_NODES = 128;
  private static final Set<PlanNodeStatus> SATISFIED =
      EnumSet.of(PlanNodeStatus.COMPLETED, PlanNodeStatus.REUSED);
  private final Map<String, Node> nodes;

  public WorkflowGraph(List<Node> input) {
    if (input == null || input.isEmpty() || input.size() > MAX_NODES)
      throw new IllegalArgumentException("任务图节点数量必须在 1 至 128 之间。");
    var checked = new LinkedHashMap<String, Node>();
    for (Node node : input) {
      if (node == null || checked.putIfAbsent(node.id(), node) != null)
        throw new IllegalArgumentException("任务图节点缺失或标识重复。");
    }
    for (Node node : checked.values()) {
      var seen = new HashSet<String>();
      for (String dependency : node.dependencies()) {
        if (!seen.add(dependency)
            || !checked.containsKey(dependency)
            || dependency.equals(node.id()))
          throw new IllegalArgumentException("任务图依赖重复、不存在或指向自身。");
      }
    }
    var visiting = new HashSet<String>();
    var visited = new HashSet<String>();
    for (String id : checked.keySet()) visit(id, checked, visiting, visited);
    this.nodes = java.util.Collections.unmodifiableMap(checked);
  }

  /** 只返回待执行且所有前置节点已完成或可靠复用的节点，保持计划顺序。 */
  public List<String> ready(Map<String, PlanNodeStatus> states) {
    if (states == null
        || !states.keySet().equals(nodes.keySet())
        || states.values().stream().anyMatch(Objects::isNull))
      throw new IllegalArgumentException("任务图状态必须完整对应所有节点。");
    List<String> ready = new ArrayList<>();
    for (Node node : nodes.values()) {
      if (states.get(node.id()) == PlanNodeStatus.PENDING
          && node.dependencies().stream().allMatch(id -> SATISFIED.contains(states.get(id)))) {
        ready.add(node.id());
      }
    }
    return List.copyOf(ready);
  }

  private static void visit(
      String id, Map<String, Node> nodes, Set<String> visiting, Set<String> visited) {
    if (visited.contains(id)) return;
    if (!visiting.add(id)) throw new IllegalArgumentException("任务图依赖存在循环。");
    for (String dependency : nodes.get(id).dependencies())
      visit(dependency, nodes, visiting, visited);
    visiting.remove(id);
    visited.add(id);
  }

  /** 节点标识独立于角色，同一角色在未来可以对应多个不同节点。 */
  public record Node(String id, List<String> dependencies) {
    public Node {
      if (id == null || !id.matches("[A-Za-z0-9_-]{1,64}"))
        throw new IllegalArgumentException("任务节点标识无效。");
      if (dependencies == null || dependencies.stream().anyMatch(Objects::isNull))
        throw new IllegalArgumentException("任务依赖不能为空。");
      dependencies = List.copyOf(dependencies);
    }
  }
}
