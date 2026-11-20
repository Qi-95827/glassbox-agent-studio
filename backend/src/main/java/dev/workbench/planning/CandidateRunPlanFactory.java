package dev.workbench.planning;

import dev.workbench.framework.workflow.plan.CandidateRunPlan;
import dev.workbench.framework.workflow.plan.PlanNodeSpec;
import java.util.ArrayList;

/** 把已通过校验的候选映射为运行计划；角色到定义的映射沿用校验结果，不重新猜测。 */
final class CandidateRunPlanFactory {
  private CandidateRunPlanFactory() {}

  static CandidateRunPlan create(
      PlanCandidateDraft candidate,
      String candidateSha256,
      java.util.List<PlanCandidateValidator.ResolvedNode> resolved) {
    var nodes = new ArrayList<CandidateRunPlan.Node>();
    for (var item : resolved) {
      var node = item.node();
      nodes.add(
          new CandidateRunPlan.Node(
              node.key(),
              node.title(),
              phase(node.role()),
              item.definition(),
              node.dependsOn(),
              node.readFiles(),
              node.writeFiles(),
              node.expectedOutputs().stream()
                  .map(
                      output ->
                          new PlanNodeSpec.Output(
                              PlanNodeSpec.Kind.valueOf(output.kind().name()),
                              output.path(),
                              output.required()))
                  .toList(),
              node.acceptanceCriteria(),
              item.binding()));
    }
    return new CandidateRunPlan(candidate.schemaVersion(), candidateSha256, nodes);
  }

  private static String phase(PlanCandidateDraft.Role role) {
    return switch (role) {
      case ANALYZE -> "analysis";
      case IMPLEMENT -> "implementation";
      case REVIEW -> "review";
    };
  }
}
