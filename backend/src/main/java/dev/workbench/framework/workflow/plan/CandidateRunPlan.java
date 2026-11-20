package dev.workbench.framework.workflow.plan;

import dev.workbench.framework.agent.definition.AgentDefinitionRef;
import dev.workbench.planning.PlanCandidateValidator;
import java.util.List;

/**
 * 候选计划物化为运行时的受控图输入。
 *
 * <p>只包含已通过规划校验的节点、依赖、文件子集和产出；模板标识、审批和运行归属由服务端补齐。
 */
public record CandidateRunPlan(int schemaVersion, String candidateSha256, List<Node> nodes) {
  public CandidateRunPlan {
    if (schemaVersion != 1 || candidateSha256 == null || !candidateSha256.matches("[0-9a-f]{64}"))
      throw new IllegalArgumentException("候选运行计划版本或摘要无效。");
    nodes = List.copyOf(nodes);
    if (nodes.isEmpty() || nodes.size() > 8) throw new IllegalArgumentException("候选运行计划节点数量无效。");
    var keys = new java.util.HashSet<String>();
    for (var node : nodes) {
      if (node == null || !keys.add(node.key()))
        throw new IllegalArgumentException("候选运行计划节点键为空或重复。");
      if (!node.readFiles().containsAll(node.writeFiles()))
        throw new IllegalArgumentException("候选运行计划的写入范围必须属于读取范围。");
    }
    for (var node : nodes)
      for (String dependency : node.dependsOn())
        if (!keys.contains(dependency) || dependency.equals(node.key()))
          throw new IllegalArgumentException("候选运行计划依赖无效：" + dependency);
  }

  public record Node(
      String key,
      String title,
      String phase,
      AgentDefinitionRef definition,
      List<String> dependsOn,
      List<String> readFiles,
      List<String> writeFiles,
      List<PlanNodeSpec.Output> outputs,
      List<String> acceptanceCriteria,
      PlanCandidateValidator.AgentBinding binding) {
    /** 兼容没有私有 Agent 绑定的旧计划构造。 */
    public Node(
        String key,
        String title,
        String phase,
        AgentDefinitionRef definition,
        List<String> dependsOn,
        List<String> readFiles,
        List<String> writeFiles,
        List<PlanNodeSpec.Output> outputs,
        List<String> acceptanceCriteria) {
      this(
          key,
          title,
          phase,
          definition,
          dependsOn,
          readFiles,
          writeFiles,
          outputs,
          acceptanceCriteria,
          null);
    }

    public Node {
      dependsOn = List.copyOf(dependsOn);
      readFiles = List.copyOf(readFiles);
      writeFiles = List.copyOf(writeFiles);
      outputs = List.copyOf(outputs);
      acceptanceCriteria = List.copyOf(acceptanceCriteria);
    }
  }
}
