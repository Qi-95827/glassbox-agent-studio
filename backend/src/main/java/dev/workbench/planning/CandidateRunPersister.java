package dev.workbench.planning;

import dev.workbench.domain.RunLimits;
import dev.workbench.domain.TaskDefinition;
import dev.workbench.framework.workflow.plan.CandidateRunPlan;
import dev.workbench.workspace.WorkspaceAuthorizationService;
import java.util.UUID;

/** 将已确认候选物化为任务与首次运行；由 orchestration 实现。 */
public interface CandidateRunPersister {
  void persistFromCandidate(
      TaskDefinition task,
      UUID runId,
      RunLimits limits,
      String message,
      CandidateRunPlan plan,
      WorkspaceAuthorizationService.Prepared workspaceScope,
      BudgetPricing pricing);
}
