package dev.workbench.orchestration;

import dev.workbench.audit.AuditService;
import dev.workbench.planning.EntryPolicyService;
import dev.workbench.planning.EntryRouteService;
import dev.workbench.external.ExternalActionService;
import dev.workbench.planning.PlanService;
import dev.workbench.memory.MemoryService;
import dev.workbench.observability.RunMetricsService;
import dev.workbench.workspace.RunHandoverService;
import dev.workbench.workspace.WorkspaceAuthorizationService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import dev.workbench.store.AgentAssemblyRecordService;
import dev.workbench.store.RunCountStore;
import dev.workbench.store.RunEvidenceRecordService;
import dev.workbench.store.RunStateStore;
import dev.workbench.store.TaskRecordService;
import dev.workbench.task.*;
import dev.workbench.planning.PlanProposalService;
import dev.workbench.planning.RecoveryLeaseService;
import dev.workbench.planning.RunSnapshotService;
import dev.workbench.planning.SourceArtifactService;

/** 工作台的任务与运行查询、任务归档及其审计。 */
@Service
public class WorkbenchTaskService {
  private final RunStateStore runs;
  private final TaskRecordService tasks;
  private final AgentAssemblyRecordService agents;
  private final RunEvidenceRecordService evidence;
  private final RunCountStore counts;
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
  private final dev.workbench.planning.RecoveryLeaseService leases;
  private final CostService costs;
  private final ExternalActionService externalActions;
  private final dev.workbench.ownership.OwnershipService ownership;

  public WorkbenchTaskService(
      RunStateStore runs,
      TaskRecordService tasks,
      AgentAssemblyRecordService agents,
      RunEvidenceRecordService evidence,
      RunCountStore counts,
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
      dev.workbench.planning.RecoveryLeaseService leases,
      CostService costs,
      ExternalActionService externalActions,
      dev.workbench.ownership.OwnershipService ownership) {
    this.runs = runs;
    this.tasks = tasks;
    this.agents = agents;
    this.evidence = evidence;
    this.counts = counts;
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
    this.ownership = ownership;
  }

  public List<Map<String, Object>> tasks() {
    return tasks.tasks().stream()
        .filter(
            row -> {
              Object id = row.get("ID");
              return id instanceof String taskId && ownership.canReadTask(taskId);
            })
        .toList();
  }

  @Transactional
  public void archive(String taskId, boolean archived) {
    ownership.requireTask(taskId);
    tasks.setTaskArchived(taskId, archived);
    audit.record(
        "TASK_ARCHIVE",
        "TASK",
        taskId,
        archived ? "ARCHIVED" : "UNARCHIVED",
        "任务列表可见性变化；运行、证据和 Git 产物保留");
  }

  public Map<String, Object> run(String id, boolean includeEvents) {
    ownership.requireRun(id);
    var run = runs.run(id);
    String taskId = run.get("TASK_ID").toString();
    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put("run", run);
    detail.put("task", tasks.task(taskId, String.valueOf(run.get("STATUS"))));
    detail.put("entryPolicy", entryPolicies.find(taskId));
    detail.put("entryRoute", entryRoutes.find(taskId));
    detail.put("sourceArtifacts", sourceArtifacts.find(taskId));
    detail.put("workspaceScope", workspaceAuthorization.find(taskId));
    detail.put(
        "planProposals", plans.workflowPlan(id) == null ? List.of() : planProposals.list(id));
    detail.put("snapshots", snapshots.find(id));
    detail.put("recoveryLease", leases.find(id));
    detail.put("costEstimate", costs.estimate(id));
    detail.put("externalActions", externalActions.findByRun(id));
    detail.put("agents", agents.agents(id));
    detail.put("agentAssemblies", agents.agentAssemblies(id));
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
    detail.put("tools", agents.tools(id));
    detail.put("models", agents.models(id));
    detail.put("handoffs", evidence.handoffs(id));
    detail.put("events", includeEvents ? evidence.events(id) : List.of());
    detail.put("checkpoints", evidence.checkpoints(id));
    detail.put("runs", runs.runs(taskId));
    detail.put("roleBudgets", tasks.roleBudgets(taskId));
    detail.put("toolBudgets", tasks.toolBudgets(taskId));
    return detail;
  }

  public Object skills(String id) {
    ownership.requireRun(id);
    runs.runSnapshot(id);
    return agents.skills(id);
  }

  public Object memoryLoad(String id) {
    ownership.requireRun(id);
    runs.runSnapshot(id);
    return memories.loadForRun(id);
  }

  public Object toolGrants(String id) {
    ownership.requireRun(id);
    runs.runSnapshot(id);
    return agents.agentToolGrants(id);
  }

  public Object handover(String id) {
    ownership.requireRun(id);
    runs.runSnapshot(id);
    return handovers.findByRun(id);
  }

  public Map<String, Object> overview() {
    return counts.overview(ownership.readableTaskIds());
  }
}
