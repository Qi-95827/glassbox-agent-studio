package dev.workbench.delivery;

import dev.workbench.delivery.ExecutionViewService.BudgetView;
import dev.workbench.delivery.ExecutionViewService.CountsView;
import dev.workbench.delivery.ExecutionViewService.HandoverView;
import dev.workbench.delivery.ExecutionViewService.InheritedView;
import dev.workbench.delivery.ExecutionViewService.RecoverySupportView;
import dev.workbench.delivery.ExecutionViewService.RecoveryView;
import dev.workbench.delivery.ExecutionViewService.ReusedNodeView;
import dev.workbench.delivery.ExecutionViewService.SupplementView;
import dev.workbench.domain.RunSnapshot;
import dev.workbench.domain.RunStatus;
import dev.workbench.framework.workflow.plan.PlanNode;
import dev.workbench.framework.workflow.plan.PlanNodeStatus;
import dev.workbench.framework.workflow.plan.WorkflowCheck;
import dev.workbench.framework.workflow.plan.WorkflowCheckpoint;
import dev.workbench.framework.workflow.plan.WorkflowPlan;
import dev.workbench.persistence.repository.SupplementRepository;
import dev.workbench.store.RunCountStore;
import dev.workbench.store.RunStateStore;
import dev.workbench.store.TaskRecordService;
import dev.workbench.task.WorkflowRecoveryService;
import dev.workbench.workspace.RunHandoverService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** 执行视图的预算、计数、恢复与补充/接手摘要投影。 */
@Component
final class ExecutionSummaryProjector {
  private final RunStateStore runs;
  private final RunCountStore counts;
  private final TaskRecordService tasks;
  private final WorkflowRecoveryService recovery;
  private final ExecutionOperationProjector operations;

  ExecutionSummaryProjector(
      RunStateStore runs,
      RunCountStore counts,
      TaskRecordService tasks,
      WorkflowRecoveryService recovery,
      ExecutionOperationProjector operations) {
    this.runs = runs;
    this.counts = counts;
    this.tasks = tasks;
    this.recovery = recovery;
    this.operations = operations;
  }

  RecoveryView recoveryView(
      String runId,
      Map<String, Object> run,
      WorkflowPlan plan,
      List<PlanNode> nodes,
      int modelCalls,
      int toolCalls) {
    String parentRunId = ExecutionViewSupport.nullable(run.get("PARENT_ID"));
    String sourceCheckpoint =
        plan == null ? null : ExecutionViewSupport.nullable(plan.sourceCheckpointId());
    if (parentRunId == null && sourceCheckpoint == null) return null;
    InheritedView inherited = null;
    if (sourceCheckpoint != null) {
      var saved = recovery.inherited(UUID.fromString(sourceCheckpoint));
      if (saved != null)
        inherited = new InheritedView(saved.rounds(), saved.tools(), saved.elapsedMs());
    }
    var reused =
        nodes.stream()
            .filter(node -> node.status() == PlanNodeStatus.REUSED)
            .map(
                node ->
                    new ReusedNodeView(
                        node.id().toString(),
                        node.key(),
                        node.label(),
                        node.sourceAgentId() == null ? null : node.sourceAgentId().toString()))
            .toList();
    return new RecoveryView(
        parentRunId, sourceCheckpoint, reused, inherited, modelCalls, toolCalls);
  }

  SupplementView supplementView(SupplementRepository.Request request, List<PlanNode> nodes) {
    if (request == null) return null;
    return new SupplementView(
        request.id(),
        request.status(),
        request.kind(),
        request.reasonCode(),
        request.question(),
        request.nodeId(),
        ExecutionViewSupport.label(nodes, java.util.UUID.fromString(request.nodeId())),
        request.answerPath(),
        request.answerSha256(),
        request.taskVersion(),
        request.createdAt(),
        request.decidedAt());
  }

  HandoverView handoverView(RunHandoverService.Handover handover, boolean revalidationRequired) {
    if (handover == null) return null;
    return new HandoverView(
        handover.id().toString(),
        handover.status(),
        handover.baselineFingerprint(),
        handover.currentFingerprint(),
        handover.evidenceInvalidated(),
        revalidationRequired,
        handover.note(),
        handover.takenAt().toString(),
        handover.returnedAt() == null ? null : handover.returnedAt().toString());
  }

  BudgetView budgetView(RunSnapshot snapshot, String runId) {
    var tokens = counts.reportedTokens(runId);
    Long remainingTokens =
        snapshot.maxReportedTokens() == null
            ? null
            : Math.max(0, snapshot.maxReportedTokens() - tokens.knownTokens());
    Long remainingRounds =
        snapshot.maxRounds() == null
            ? null
            : Math.max(0L, snapshot.maxRounds() - snapshot.rounds());
    Long remainingTools =
        snapshot.maxTools() == null ? null : Math.max(0L, snapshot.maxTools() - snapshot.tools());
    Long remainingMs =
        snapshot.maxSeconds() == null
            ? null
            : Math.max(0L, snapshot.maxSeconds() * 1000L - snapshot.elapsedMs());
    return new BudgetView(
        snapshot.maxRounds(),
        snapshot.rounds(),
        remainingRounds,
        snapshot.maxTools(),
        snapshot.tools(),
        remainingTools,
        snapshot.maxReportedTokens(),
        tokens.knownTokens(),
        tokens.unknownCalls(),
        remainingTokens,
        snapshot.maxSeconds(),
        snapshot.elapsedMs(),
        remainingMs,
        snapshot.parentId() != null);
  }

  /** 中断运行是否可恢复的明确原因；不根据节点顺序猜测。 */
  RecoverySupportView recoverySupportView(RunSnapshot snapshot, WorkflowPlan plan, String runId) {
    if (snapshot.status() != RunStatus.INTERRUPTED) return null;
    if (runs.hasRecovery(runId))
      return new RecoverySupportView(false, "此运行已经发起恢复，请查看关联子运行。");
    var task = tasks.taskDefinition(snapshot.taskId().toString());
    if (plan != null) {
      if (plan.sourceCheckpointId() != null) return new RecoverySupportView(false, "恢复子运行不支持再次恢复。");
      if (plan.scope() != dev.workbench.framework.workflow.plan.WorkflowTemplate.Scope.TEXT_ONLY
          && plan.scope()
              != dev.workbench.framework.workflow.plan.WorkflowTemplate.Scope.WORKSPACE_READ_ONLY)
        return new RecoverySupportView(false, "当前写入、Bug 或外部动作流程尚不支持自动恢复。");
      var checkpoints = operations.recoveryCheckpoints(runId, plan);
      if (checkpoints.stream().anyMatch(WorkflowCheckpoint::available))
        return new RecoverySupportView(true, "可从已结算节点边界恢复，预算和复用来源已记录。");
      return new RecoverySupportView(false, "没有可用检查点：节点未结算、文件或授权已变化，或检查点保存被跳过。");
    }
    if (task.writeEnabled() || task.bugId() != null || task.memoryEnabled())
      return new RecoverySupportView(false, "写入、模拟 Bug 或启用项目背景的任务不支持自动恢复。");
    if (task.workspaceId() != null) return new RecoverySupportView(false, "该工作区任务没有可安全恢复的检查点。");
    return new RecoverySupportView(false, "只有支持的分析检查点可以恢复。");
  }

  CountsView counts(
      List<PlanNode> nodes,
      List<WorkflowCheck> checks,
      List<Map<String, Object>> models,
      List<Map<String, Object>> tools) {
    return new CountsView(
        nodes.size(),
        countStatus(nodes, PlanNodeStatus.COMPLETED),
        countStatus(nodes, PlanNodeStatus.REUSED),
        countStatus(nodes, PlanNodeStatus.RUNNING),
        countStatus(nodes, PlanNodeStatus.PENDING),
        countStatus(nodes, PlanNodeStatus.FAILED),
        countStatus(nodes, PlanNodeStatus.SKIPPED),
        checks.size(),
        models.size(),
        tools.size(),
        (int)
            models.stream()
                .filter(row -> row.get("INPUT_TOKENS") == null || row.get("OUTPUT_TOKENS") == null)
                .count());
  }

  private static long countStatus(List<PlanNode> nodes, PlanNodeStatus status) {
    return nodes.stream().filter(node -> node.status() == status).count();
  }
}
