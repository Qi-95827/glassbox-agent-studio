package dev.workbench.task;

import dev.workbench.audit.AuditService;
import dev.workbench.domain.EntryPolicyService;
import dev.workbench.domain.EntryRouteService;
import dev.workbench.external.ExternalActionService;
import dev.workbench.framework.workflow.plan.PlanService;
import dev.workbench.memory.MemoryService;
import dev.workbench.observability.RunMetricsService;
import dev.workbench.task.CostService;
import dev.workbench.task.PlanProposalService;
import dev.workbench.task.RunSnapshotService;
import dev.workbench.task.SourceArtifactService;
import dev.workbench.workspace.RunHandoverService;
import dev.workbench.workspace.WorkspaceAuthorizationService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 工作台的任务与运行查询、任务归档及其审计。 */
@Service
public class WorkbenchTaskService {
  private final RunRecordService repo;
  private final PlanService plans;
  private final WorkflowExecutionService workflowRuns;
  private final WorkflowRecoveryService workflowRecovery;
  private final MemoryService memories;
  private final RunMetricsService metrics;
  private final RunHandoverService handovers;
  private final AuditService audit;
  private final EntryPolicyService entryPolicies;
  private final EntryRouteService entryRoutes;
  private final SourceArtifactService sourceArtifacts;
  private final WorkspaceAuthorizationService workspaceAuthorization;
  private final PlanProposalService planProposals;
  private final RunSnapshotService snapshots;
  private final dev.workbench.task.RecoveryLeaseService leases;
  private final CostService costs;
  private final ExternalActionService externalActions;

  public WorkbenchTaskService(
      RunRecordService repo,
      PlanService plans,
      WorkflowExecutionService workflowRuns,
      WorkflowRecoveryService workflowRecovery,
      MemoryService memories,
      RunMetricsService metrics,
      RunHandoverService handovers,
      AuditService audit,
      EntryPolicyService entryPolicies,
      EntryRouteService entryRoutes,
      SourceArtifactService sourceArtifacts,
      WorkspaceAuthorizationService workspaceAuthorization,
      PlanProposalService planProposals,
      RunSnapshotService snapshots,
      dev.workbench.task.RecoveryLeaseService leases,
      CostService costs,
      ExternalActionService externalActions) {
    this.repo = repo;
    this.plans = plans;
    this.workflowRuns = workflowRuns;
    this.workflowRecovery = workflowRecovery;
    this.memories = memories;
    this.metrics = metrics;
    this.handovers = handovers;
    this.audit = audit;
    this.entryPolicies = entryPolicies;
    this.entryRoutes = entryRoutes;
    this.sourceArtifacts = sourceArtifacts;
    this.workspaceAuthorization = workspaceAuthorization;
    this.planProposals = planProposals;
    this.snapshots = snapshots;
    this.leases = leases;
    this.costs = costs;
    this.externalActions = externalActions;
  }

  public List<Map<String, Object>> tasks() {
    return repo.tasks();
  }

  @Transactional
  public void archive(String taskId, boolean archived) {
    repo.setTaskArchived(taskId, archived);
    audit.record(
        "TASK_ARCHIVE",
        "TASK",
        taskId,
        archived ? "ARCHIVED" : "UNARCHIVED",
        "任务列表可见性变化；运行、证据和 Git 产物保留");
  }

  public Map<String, Object> run(String id, boolean includeEvents) {
    var run = repo.run(id);
    String taskId = run.get("TASK_ID").toString();
    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put("run", run);
    detail.put("task", repo.task(taskId));
    detail.put("entryPolicy", entryPolicies.find(taskId));
    detail.put("entryRoute", entryRoutes.find(taskId));
    detail.put("sourceArtifacts", sourceArtifacts.find(taskId));
    detail.put("workspaceScope", workspaceAuthorization.find(taskId));
    detail.put("planProposals", plans.workflowPlan(id) == null ? List.of() : planProposals.list(id));
    detail.put("snapshots", snapshots.find(id));
    detail.put("recoveryLease", leases.find(id));
    detail.put("costEstimate", costs.estimate(id));
    detail.put("externalActions", externalActions.findByRun(id));
    detail.put("agents", repo.agents(id));
    detail.put("agentAssemblies", repo.agentAssemblies(id));
    detail.put("planNodes", plans.find(id));
    detail.put("workflowMetrics", metrics.workflow(id));
    var workflow = plans.workflowPlan(id);
    if (workflow != null) {
      detail.put("workflowPlan", workflow);
      detail.put("workflowAttempts", workflowRuns.workflowAttempts(id));
      detail.put("workflowChecks", workflowRuns.workflowChecks(id));
      detail.put("workflowHandoffs", workflowRuns.workflowHandoffs(id));
      detail.put("workflowCheckpoints", workflowRecovery.checkpoints(id, workflow));
    }
    detail.put("tools", repo.tools(id));
    detail.put("models", repo.models(id));
    detail.put("handoffs", repo.handoffs(id));
    detail.put("events", includeEvents ? repo.events(id) : List.of());
    detail.put("checkpoints", repo.checkpoints(id));
    detail.put("runs", repo.runs(taskId));
    detail.put("roleBudgets", repo.roleBudgets(taskId));
    detail.put("toolBudgets", repo.toolBudgets(taskId));
    return detail;
  }

  public Object skills(String id) {
    repo.runSnapshot(id);
    return repo.skills(id);
  }

  public Object memoryLoad(String id) {
    repo.runSnapshot(id);
    return memories.loadForRun(id);
  }

  public Object toolGrants(String id) {
    repo.runSnapshot(id);
    return repo.agentToolGrants(id);
  }

  public Object handover(String id) {
    repo.runSnapshot(id);
    return handovers.findByRun(id);
  }

  public Map<String, Object> overview() {
    return repo.overview();
  }
}
