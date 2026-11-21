package dev.workbench.orchestration;

import dev.workbench.bug.BugIntakeService;
import dev.workbench.planning.EntryPolicyService;
import dev.workbench.planning.EntryRouteService;
import dev.workbench.domain.TaskDefinition;
import dev.workbench.framework.workflow.plan.WorkflowTemplate;
import dev.workbench.task.CostService;
import dev.workbench.domain.RunLimits;
import dev.workbench.store.RunCreationStore;
import dev.workbench.workspace.WorkspaceAuthorizationService;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import dev.workbench.framework.workflow.plan.CandidateRunPlan;
import dev.workbench.framework.workflow.plan.CandidateRunPlan;

/**
 * 创建任务的唯一业务提交边界。
 *
 * <p>任务、首运行、初始计划、角色/工具预算、入口策略、Bug 接收记录和创建事件在同一事务中写入。
 * 调用方必须在本方法返回（事务已提交）后再派发执行；任一写入失败时全部回滚，不留下可执行的半成品。
 */
@Service
public class TaskCreationService implements dev.workbench.planning.CandidateRunPersister {
  private final RunCreationStore runs;
  private final BugIntakeService intakes;
  private final EntryPolicyService entryPolicies;
  private final EntryRouteService entryRoutes;
  private final CostService costs;
  private final WorkspaceAuthorizationService workspaceAuthorization;
  private final TaskCreationHooks hooks;

  public TaskCreationService(
      RunCreationStore runs,
      BugIntakeService intakes,
      EntryPolicyService entryPolicies,
      EntryRouteService entryRoutes,
      CostService costs,
      WorkspaceAuthorizationService workspaceAuthorization,
      TaskCreationHooks hooks) {
    this.runs = runs;
    this.intakes = intakes;
    this.entryPolicies = entryPolicies;
    this.entryRoutes = entryRoutes;
    this.costs = costs;
    this.workspaceAuthorization = workspaceAuthorization;
    this.hooks = hooks;
  }

  @Transactional
  public void persist(
      TaskDefinition task,
      UUID runId,
      RunLimits limits,
      String message,
      WorkflowTemplate workflow,
      boolean reviewRequired,
      EntryPolicyService.Validation entryValidation) {
    persist(
        task, runId, limits, message, workflow, reviewRequired, entryValidation, null, null, null);
  }

  @Transactional
  public void persist(
      TaskDefinition task,
      UUID runId,
      RunLimits limits,
      String message,
      WorkflowTemplate workflow,
      boolean reviewRequired,
      EntryPolicyService.Validation entryValidation,
      EntryRouteService.Decision route,
      WorkspaceAuthorizationService.Prepared workspaceScope,
      Long maxCostMicros) {
    runs.createTaskAndFirstRun(task, runId, limits, message, workflow, reviewRequired);
    if (task.bugId() != null) intakes.record(task, runId.toString());
    if (entryValidation != null) {
      hooks.runBeforeEntryPolicySave();
      entryPolicies.save(task.id().toString(), entryValidation);
    }
    if (route != null) entryRoutes.save(task.id().toString(), route);
    if (workspaceScope != null)
      workspaceAuthorization.save(task.id().toString(), task.workspaceId(), workspaceScope);
    if (maxCostMicros != null) costs.bindLimit(runId.toString(), task.modelMode(), maxCostMicros);
  }

  /**
   * 已确认候选的唯一创建事务。
   *
   * <p>任务、首运行、计划、节点规格、授权范围和费用上限一起提交；调用方在同一事务内消费候选， 任一写入失败时不会留下可派发的运行。
   */
  @Transactional
  public void persistFromCandidate(
      TaskDefinition task,
      UUID runId,
      RunLimits limits,
      String message,
      dev.workbench.framework.workflow.plan.CandidateRunPlan plan,
      WorkspaceAuthorizationService.Prepared workspaceScope,
      dev.workbench.planning.BudgetPricing pricing) {
    runs.createTaskAndFirstRunFromPlan(
        task, runId, limits, message, plan, java.time.Instant.now().toString());
    if (workspaceScope != null)
      workspaceAuthorization.save(task.id().toString(), task.workspaceId(), workspaceScope);
    if (pricing != null) costs.bindLimit(runId.toString(), pricing);
  }
}
