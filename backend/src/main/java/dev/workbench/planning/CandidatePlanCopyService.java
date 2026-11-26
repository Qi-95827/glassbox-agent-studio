package dev.workbench.planning;

import dev.workbench.framework.workflow.plan.CandidateRunPlan;
import dev.workbench.framework.workflow.plan.PlanNode;
import dev.workbench.framework.workflow.plan.PlanNodeSpec;
import dev.workbench.framework.workflow.plan.RevisionEdge;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;
import org.springframework.stereotype.Service;

/**
 * 按源运行的候选受控图重建一份可物化的图。
 *
 * <p>节点顺序、依赖、读写范围与产出原样复制；验收条件由调用方按节点计算，用于同任务迭代注入本轮调改要求。 只有全部节点都具备规格、定义引用与键的可复制图才会被接受，缺任何一项就交由调用方走任务既有执行路径，
 * 而不是复制一份跑不起来的图。
 */
@Service
public class CandidatePlanCopyService {
  /** 候选计划最多 8 个节点，复制结果必须满足同一上限。 */
  private static final int MAX_NODES = 8;

  private final PlanService plans;

  public CandidatePlanCopyService(PlanService plans) {
    this.plans = plans;
  }

  /** 源运行的图是否可以被完整复制；系统检查节点与缺失规格的历史图返回 false。 */
  public boolean copyable(String sourceRunId) {
    List<PlanNode> nodes = plans.find(sourceRunId);
    if (nodes.isEmpty() || nodes.size() > MAX_NODES) return false;
    Map<UUID, PlanNodeSpec> specs = plans.specsByNode(sourceRunId);
    for (PlanNode node : nodes) {
      if (node.systemCheck()
          || node.definitionRef() == null
          || node.key() == null
          || node.label() == null
          || node.phase() == null
          || !specs.containsKey(node.id())) return false;
    }
    return true;
  }

  /** 复制整张图；验收条件由调用方给出，其余字段保持源计划原值。 */
  public CandidateRunPlan copy(
      String sourceRunId, BiFunction<PlanNode, PlanNodeSpec, List<String>> acceptance) {
    List<PlanNode> nodes = plans.find(sourceRunId);
    Map<UUID, PlanNodeSpec> specs = plans.specsByNode(sourceRunId);
    Map<UUID, String> keysById = new LinkedHashMap<>();
    for (PlanNode node : nodes) keysById.put(node.id(), node.key());
    var planNodes = new ArrayList<CandidateRunPlan.Node>();
    for (PlanNode node : nodes) {
      PlanNodeSpec spec = specs.get(node.id());
      if (spec == null) throw new IllegalStateException("源计划节点 " + node.key() + " 缺少规格，无法安全复制。");
      if (node.definitionRef() == null)
        throw new IllegalStateException("源计划节点 " + node.key() + " 缺少定义引用，无法安全复制。");
      var dependencies = new ArrayList<String>();
      for (UUID dependency : node.dependencyIds()) {
        String key = keysById.get(dependency);
        if (key == null) throw new IllegalStateException("源计划节点 " + node.key() + " 的依赖不在同一计划内。");
        dependencies.add(key);
      }
      planNodes.add(
          new CandidateRunPlan.Node(
              node.key(),
              node.label(),
              node.phase(),
              node.definitionRef(),
              dependencies,
              spec.readFiles(),
              spec.writeFiles(),
              spec.outputs(),
              acceptance.apply(node, spec),
              null));
    }
    return new CandidateRunPlan(1, digest(planNodes), planNodes, edges(sourceRunId, keysById));
  }

  /** 反思回边按节点键重新指向新图；指向不存在节点的历史回边不再复制。 */
  private List<RevisionEdge> edges(String sourceRunId, Map<UUID, String> keysById) {
    var result = new ArrayList<RevisionEdge>();
    for (var edge : plans.revisionEdges(sourceRunId)) {
      String reviewer = keysById.get(edge.reviewerNodeId());
      String target = keysById.get(edge.targetNodeId());
      if (reviewer == null || target == null) continue;
      result.add(new RevisionEdge(reviewer, target, edge.maxRevisions()));
    }
    return List.copyOf(result);
  }

  /** 图摘要必须由受控图内容决定，保证同一份复制图在任一次重建后摘要一致。 */
  private static String digest(List<CandidateRunPlan.Node> nodes) {
    var digest = new CanonicalDigest("run-iteration-plan-v1");
    digest.field(nodes.size());
    for (var node : nodes) {
      digest.field(node.key());
      digest.field(node.title());
      digest.field(node.phase());
      digest.field(node.definition().id());
      digest.field(node.definition().version());
      digest.field(node.dependsOn().size());
      for (String dependency : node.dependsOn()) digest.field(dependency);
      digest.field(node.readFiles().size());
      for (String path : node.readFiles()) digest.field(path);
      digest.field(node.writeFiles().size());
      for (String path : node.writeFiles()) digest.field(path);
      digest.field(node.outputs().size());
      for (var output : node.outputs())
        digest.field(output.kind().name()).field(output.path()).field(String.valueOf(output.required()));
      digest.field(node.acceptanceCriteria().size());
      for (String item : node.acceptanceCriteria()) digest.field(item);
    }
    return digest.hex();
  }
}
