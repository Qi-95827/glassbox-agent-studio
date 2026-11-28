package dev.workbench.task;

import dev.workbench.domain.EventRef;
import dev.workbench.domain.EventType;
import dev.workbench.domain.Handoff;
import dev.workbench.domain.RunStatus;
import dev.workbench.domain.StopCause;
import dev.workbench.framework.workflow.check.SystemCheckInput;
import dev.workbench.framework.workflow.check.SystemCheckResult;
import dev.workbench.framework.workflow.check.SystemCheckStatus;
import dev.workbench.framework.workflow.plan.PlanNode;
import dev.workbench.framework.workflow.plan.PlanNodeKind;
import dev.workbench.framework.workflow.plan.WorkflowAttempt;
import dev.workbench.framework.workflow.plan.WorkflowCheck;
import dev.workbench.framework.workflow.plan.WorkflowHandoff;
import dev.workbench.framework.workflow.plan.WorkflowHandoff.ToolEvidence;
import dev.workbench.persistence.repository.PlanGraphRepository;
import dev.workbench.persistence.repository.RunRepository;
import dev.workbench.persistence.repository.SupplementRepository;
import dev.workbench.persistence.repository.WorkflowExecutionRepository;
import dev.workbench.persistence.repository.view.CheckReceipt;
import dev.workbench.planning.PlanService;
import dev.workbench.store.AgentAssemblyRecordService;
import dev.workbench.store.RunCountStore;
import dev.workbench.store.RunEvidenceRecordService;
import dev.workbench.store.RunStateStore;
import dev.workbench.store.TaskRecordService;
import dev.workbench.workspace.WorkspaceService;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 维护动态计划的尝试与交接，复用运行仓库的实例与终态事务。
 *
 * <p>认领、结算、停机收口与视图组装分别由同包协作类承担；本类保留 Bean 身份、事务入口与全部既有公开方法签名，
 * 程序化事务仍由同一个 {@link TransactionTemplate} 发起，调用方语义完全不变。
 */
@Service
public class WorkflowExecutionService {
  private final RunStateStore runs;
  private final TaskRecordService tasks;
  private final PlanService plans;
  private final WorkflowRecoveryService recovery;
  private final RunRepository runRows;
  private final PlanGraphRepository planRows;
  private final RunEvidenceRecordService evidence;
  private final TransactionTemplate tx;
  private final WorkflowRunGuard guard;
  private final WorkflowNodeClaimer claimer;
  private final WorkflowNodeSettler settler;
  private final WorkflowStoppedRunSettler stoppedRuns;
  private final WorkflowViewAssembler views;

  public WorkflowExecutionService(
      RunStateStore runs,
      TaskRecordService tasks,
      AgentAssemblyRecordService agents,
      RunEvidenceRecordService evidence,
      RunCountStore counts,
      WorkflowExecutionRepository records,
      TransactionTemplate tx,
      PlanService plans,
      WorkflowRecoveryService recovery,
      WorkspaceService workspaces,
      RunRepository runRows,
      PlanGraphRepository planRows,
      SupplementRepository supplements) {
    this.runs = runs;
    this.tasks = tasks;
    this.plans = plans;
    this.recovery = recovery;
    this.runRows = runRows;
    this.planRows = planRows;
    this.evidence = evidence;
    this.tx = tx;
    var inputs = new WorkflowInputProvider(records);
    this.guard =
        new WorkflowRunGuard(
            runs, tasks, records, plans, recovery, runRows, workspaces, inputs);
    this.claimer =
        new WorkflowNodeClaimer(runs, tasks, agents, evidence, records, tx, plans, guard, inputs);
    this.settler =
        new WorkflowNodeSettler(
            runs,
            tasks,
            agents,
            evidence,
            counts,
            records,
            tx,
            plans,
            recovery,
            supplements,
            guard,
            inputs);
    this.stoppedRuns = new WorkflowStoppedRunSettler(runs, agents, evidence, records, tx, settler);
    this.views = new WorkflowViewAssembler(records, tasks, runs, workspaces);
  }

  /** 固定图上的认领、尝试、实例和多来源交接一起提交。 */
  public ClaimedNode claimWorkflowNode(String runId, UUID version, UUID nodeId, String goal) {
    return claimer.claim(runId, version, nodeId, goal);
  }

  /**
   * 在写集互斥前提下认领一个就绪节点。
   *
   * <p>同一运行允许最多 {@code maxParallelNodes} 个在途节点；候选计划使用节点规格的写入集合判断互斥，
   * 历史模板没有规格时对两个写节点保守地视为冲突。认领仍使用数据库条件更新，重复派发会被拒绝。
   */
  public ClaimedNode claimWorkflowNode(
      String runId, UUID version, UUID nodeId, String goal, int maxParallelNodes) {
    return claimer.claim(runId, version, nodeId, goal, maxParallelNodes);
  }

  public record ClaimedNode(
      PlanNode node,
      String agentId,
      List<WorkflowInput> dependencies,
      WorkspaceGuard workspaceGuard,
      int attemptNumber,
      int revisionIndex) {}

  /** 认领时的工作区指纹；执行前必须仍一致。 */
  public record WorkspaceGuard(String workspaceId, String fingerprint) {}

  public record ClaimedCheck(
      PlanNode node,
      String checkId,
      int attemptNo,
      List<SystemCheckInput> inputs,
      WorkspaceGuard workspaceGuard) {}

  /**
   * 认领系统检查节点：节点、检查记录和输入引用在同一事务提交。
   *
   * <p>不创建 Agent 实例或尝试，因此模型和工具调用为零；依赖结果仍从原权威表读取，检查冻结本次判定所见的来源版本。
   */
  public ClaimedCheck claimWorkflowCheck(String runId, UUID version, UUID nodeId) {
    return claimer.claimCheck(runId, version, nodeId);
  }

  /**
   * 结算系统检查。
   *
   * @return 运行是否已经结束（完成或因检查失败阻塞）
   */
  public boolean finishWorkflowCheck(
      String runId,
      UUID version,
      String checkId,
      SystemCheckResult result,
      int rounds,
      int tools,
      long elapsed) {
    return settler.finishCheck(runId, version, checkId, result, rounds, tools, elapsed);
  }

  /** 仅接受已结算的限流拒绝；带有工具调用历史的尝试原样结束。 */
  public boolean requeueAfterRateLimit(String runId, UUID version, String agentId) {
    return settler.requeueAfterRateLimit(runId, version, agentId);
  }

  /** 转移已授权的直接依赖结果及来源，可追溯到安全恢复中复用的实例。 */
  public record WorkflowInput(
      PlanNodeKind kind,
      String key,
      UUID nodeId,
      UUID dependencyNodeId,
      UUID sourceRunId,
      String label,
      UUID attemptId,
      UUID agentId,
      UUID checkId,
      SystemCheckStatus checkStatus,
      String result,
      String evidence,
      List<ToolEvidence> toolEvidence) {
    public WorkflowInput {
      toolEvidence = toolEvidence == null ? List.of() : List.copyOf(toolEvidence);
      if (kind == PlanNodeKind.AGENT && (attemptId == null || agentId == null))
        throw new IllegalArgumentException("Agent 交接缺少尝试或实例标识。");
      if (kind == PlanNodeKind.SYSTEM_CHECK && (checkId == null || attemptId != null))
        throw new IllegalArgumentException("系统检查交接缺少检查标识或混入 Agent 尝试。");
    }

    /** 检查输入需要区分来源类型，Agent 结果沿用原有字段。 */
    public SystemCheckInput toCheckInput() {
      return new SystemCheckInput(
          dependencyNodeId,
          key,
          label,
          kind,
          attemptId,
          checkId,
          true,
          checkStatus,
          result,
          toolEvidence);
    }
  }

  /**
   * 认领后、节点实际执行前的最后一道工作区版本检查。
   *
   * <p>该检查在事务外执行，覆盖“指纹预检后、模型/工具/容器启动前”的外部编辑窗口。
   */
  public void requireWorkspaceCurrent(WorkspaceGuard guard) {
    this.guard.requireWorkspaceCurrent(guard);
  }

  public void checkpointBeforeFirstNode(String runId, UUID version, long elapsed) {
    tx.executeWithoutResult(
        ignored -> {
          var nodes = guard.lock(runId, version);
          recovery.saveBoundary(runId, plans.workflowPlan(runId), nodes, elapsed);
        });
  }

  /** 结算节点与后续安全边界；最后一个节点与运行终态一起提交。 */
  public boolean finishWorkflowNode(
      String runId,
      UUID version,
      String agentId,
      String result,
      int rounds,
      int tools,
      long elapsed) {
    return settler.finishNode(runId, version, agentId, result, rounds, tools, elapsed);
  }

  /**
   * 结算节点并处理有界反思。
   *
   * <p>{@code criticVerdictJson} 非空且审查判定需要修改时，在同一事务内把目标与审查节点退回待执行； 超过回边上限则阻塞运行并等待人工审核，绝不继续无界循环。返回的
   * {@code runEnded} 表示运行已经终止。
   */
  public NodeSettlement settleWorkflowNode(
      String runId,
      UUID version,
      String agentId,
      String result,
      int rounds,
      int tools,
      long elapsed,
      String criticVerdictJson) {
    return settler.settleNode(
        runId, version, agentId, result, rounds, tools, elapsed, criticVerdictJson);
  }

  /** 节点结算结果：运行是否终止、是否触发反思，以及可审计的安全说明。 */
  public record NodeSettlement(boolean runEnded, boolean revisionTriggered, String message) {}

  @Transactional
  public void approveWorkflow(String runId, UUID version) {
    if (!runRows.lockForUpdate(runId)) throw new IllegalArgumentException("运行不存在。");
    var plan = plans.workflowPlan(runId);
    if (runs.runSnapshot(runId).status() != RunStatus.PENDING
        || plan == null
        || !plan.id().equals(version)
        || !plan.reviewRequired()
        || plan.approvedAt() != null) throw new IllegalStateException("当前运行没有待确认的该计划版本。");
    plans.checkedWorkflowNodes(runId, plan);
    plan.requireTaskAuthorization(
        tasks.taskDefinition(runs.runSnapshot(runId).taskId().toString()));
    int changed =
        planRows.approveIfPending(
            version.toString(), runId, dev.workbench.domain.WorkbenchTime.now());
    if (changed != 1) throw new IllegalStateException("当前运行没有待确认的该计划版本。");
    evidence.event(
        runId, EventType.PLAN_APPROVED, "用户确认了工作流计划 " + version + "。", EventRef.plan(version));
  }

  public List<WorkflowAttempt> workflowAttempts(String runId) {
    return views.attempts(runId);
  }

  /** 按计划顺序返回系统检查尝试。 */
  public List<WorkflowCheck> workflowChecks(String runId) {
    return views.checks(runId);
  }

  public List<CheckReceipt> workflowCheckReceipts(String runId, UUID nodeId) {
    return views.checkReceipts(runId, nodeId);
  }

  public List<WorkflowHandoff> workflowHandoffs(String runId) {
    return views.handoffs(runId);
  }

  /** 动态工作流的停止事实来自尝试终态和事件，无需构造旧版角色交接。 */
  public void finishWorkflowStoppedRun(
      String runId,
      UUID agentId,
      StopCause cause,
      String reason,
      int rounds,
      int tools,
      long elapsed) {
    stoppedRuns.finishStopped(runId, agentId, cause, reason, rounds, tools, elapsed);
  }

  /** 并行调度停止时逐个结算所有在途实例，再统一结算运行。 */
  public void finishParallelStoppedRun(
      String runId,
      List<UUID> agentIds,
      StopCause cause,
      String reason,
      int rounds,
      int tools,
      long elapsed) {
    stoppedRuns.finishParallelStopped(runId, agentIds, cause, reason, rounds, tools, elapsed);
  }

  /**
   * 并行调度停止时逐个结算所有在途实例，再统一结算运行与停机交接。
   *
   * <p>收口包 G-2：工作流引擎取代遗留直跑轨后，非成功停机同样必须留下可读的结构化系统交接，否则玻璃盒的
   * 「交接」视图对新运行会凭空缺一条最关键的终止证据。交接与运行终态在同一事务内提交，绝不出现只有终态没有交接的半成品。
   */
  public void finishParallelStoppedRun(
      String runId,
      List<UUID> agentIds,
      StopCause cause,
      String reason,
      int rounds,
      int tools,
      long elapsed,
      Handoff systemHandoff) {
    stoppedRuns.finishParallelStopped(
        runId, agentIds, cause, reason, rounds, tools, elapsed, systemHandoff);
  }
}
