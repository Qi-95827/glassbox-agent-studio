package dev.workbench.delivery;

import static dev.workbench.delivery.ExecutionViewSupport.downstreamPending;
import static dev.workbench.delivery.ExecutionViewSupport.latestCheck;
import static dev.workbench.delivery.ExecutionViewSupport.safe;
import static dev.workbench.delivery.ExecutionViewSupport.statusText;

import dev.workbench.domain.EventType;
import dev.workbench.domain.RunStatus;
import dev.workbench.delivery.ExecutionViewService.ActionView;
import dev.workbench.delivery.ExecutionViewService.BlockingView;
import dev.workbench.framework.workflow.check.SystemCheckStatus;
import dev.workbench.framework.workflow.plan.PlanNode;
import dev.workbench.framework.workflow.plan.PlanNodeStatus;
import dev.workbench.framework.workflow.plan.WorkflowCheck;
import dev.workbench.framework.workflow.plan.WorkflowCheckpoint;
import dev.workbench.framework.workflow.plan.WorkflowPlan;
import dev.workbench.persistence.repository.EventRepository;
import dev.workbench.task.WorkflowRecoveryService;
import dev.workbench.workspace.RunHandoverService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** 执行视图的阻塞分类与可用操作判定；只依据已持久化状态，不补造动作。 */
@Component
final class ExecutionOperationProjector {
  private final EventRepository events;
  private final WorkflowRecoveryService recovery;

  ExecutionOperationProjector(EventRepository events, WorkflowRecoveryService recovery) {
    this.events = events;
    this.recovery = recovery;
  }

  List<BlockingView> blocking(
      RunStatus status,
      WorkflowPlan plan,
      List<PlanNode> nodes,
      Map<UUID, List<WorkflowCheck>> checks) {
    var result = new ArrayList<BlockingView>();
    boolean nodeBlocking = false;
    if (plan != null
        && plan.reviewRequired()
        && plan.approvedAt() == null
        && status == RunStatus.PENDING) {
      result.add(
          new BlockingView(
              "PLAN_APPROVAL_REQUIRED",
              "计划已保存，等待用户确认后才会启动首个节点。",
              "RUN",
              null,
              null,
              nodes.stream()
                  .filter(node -> node.status() == PlanNodeStatus.PENDING)
                  .map(node -> node.id().toString())
                  .toList()));
    }
    for (var node : nodes) {
      if (node.systemCheck()) {
        var latest = latestCheck(checks.getOrDefault(node.id(), List.of()));
        if (latest.isPresent()
            && latest.get().status() != SystemCheckStatus.PASSED
            && latest.get().status() != SystemCheckStatus.RUNNING) {
          var check = latest.get();
          nodeBlocking = true;
          result.add(
              new BlockingView(
                  "CHECK_FAILED",
                  "系统检查" + statusText(check.status()) + "：" + safe(check.summary(), "未记录原因"),
                  "NODE",
                  node.id().toString(),
                  node.label(),
                  downstreamPending(nodes, node.id())));
        }
        continue;
      }
      if (node.status() == PlanNodeStatus.FAILED
          || node.status() == PlanNodeStatus.LIMITED
          || node.status() == PlanNodeStatus.CANCELLED
          || node.status() == PlanNodeStatus.INTERRUPTED) {
        nodeBlocking = true;
        result.add(
            new BlockingView(
                "NODE_FAILED",
                node.label() + "当前状态：" + node.status().name() + "。",
                "NODE",
                node.id().toString(),
                node.label(),
                downstreamPending(nodes, node.id())));
      }
    }
    boolean dependencyBlocking = false;
    for (var node : nodes) {
      if (node.status() != PlanNodeStatus.PENDING || node.dependencyIds().isEmpty()) continue;
      var blockedBy =
          node.dependencyIds().stream()
              .map(
                  id ->
                      nodes.stream()
                          .filter(candidate -> candidate.id().equals(id))
                          .findFirst()
                          .orElse(null))
              .filter(Objects::nonNull)
              .filter(
                  dependency ->
                      dependency.status() == PlanNodeStatus.FAILED
                          || dependency.status() == PlanNodeStatus.LIMITED
                          || dependency.status() == PlanNodeStatus.CANCELLED
                          || dependency.status() == PlanNodeStatus.INTERRUPTED
                          || dependency.status() == PlanNodeStatus.SKIPPED)
              .toList();
      if (!blockedBy.isEmpty()) {
        dependencyBlocking = true;
        result.add(
            new BlockingView(
                "DEPENDENCY_NOT_STARTED",
                "等待未结算的前置步骤："
                    + blockedBy.stream()
                        .map(PlanNode::label)
                        .collect(java.util.stream.Collectors.joining("、")),
                "NODE",
                node.id().toString(),
                node.label(),
                List.of(node.id().toString())));
      }
    }
    if (status == RunStatus.PAUSED) {
      result.add(new BlockingView("RUN_PAUSED", "运行已暂停，等待用户继续。", "RUN", null, null, List.of()));
    } else if (status == RunStatus.INTERRUPTED) {
      result.add(
          new BlockingView(
              "RUN_INTERRUPTED", "运行已中断，结果可能未知；可从可用检查点恢复。", "RUN", null, null, List.of()));
    } else if (status == RunStatus.BLOCKED && !nodeBlocking && !dependencyBlocking) {
      result.add(
          new BlockingView("RUN_BLOCKED", "运行等待用户处理，服务端未记录具体阻塞节点。", "RUN", null, null, List.of()));
    }
    return List.copyOf(result);
  }

  List<ActionView> actions(
      RunStatus status,
      String runId,
      WorkflowPlan plan,
      List<PlanNode> nodes,
      List<WorkflowCheckpoint> checkpoints,
      RunHandoverService.Handover handover,
      boolean revalidationRequired) {
    var result = new ArrayList<ActionView>();
    if (plan != null
        && plan.reviewRequired()
        && plan.approvedAt() == null
        && status == RunStatus.PENDING)
      result.add(
          new ActionView(
              "APPROVE_PLAN",
              "确认计划并运行",
              true,
              null,
              runId,
              plan.id().toString(),
              null,
              "计划 v" + plan.version()));
    boolean pendingPause = pendingPauseRequest(runId);
    if (status == RunStatus.RUNNING && !pendingPause)
      result.add(new ActionView("PAUSE", "请求暂停", true, null, runId, null, null, "当前运行"));
    boolean handoverActive = handover != null && "ACTIVE".equals(handover.status());
    if ((status == RunStatus.PAUSED || (status == RunStatus.RUNNING && pendingPause))
        && !handoverActive)
      result.add(
          new ActionView(
              "RESUME",
              revalidationRequired ? "继续执行（需重新验证）" : "继续执行",
              !revalidationRequired,
              revalidationRequired ? "人工修改后代码版本变化，旧测试与审批失效；请重新验证后再继续。" : null,
              runId,
              null,
              null,
              "当前运行"));
    if (status == RunStatus.PAUSED
        && plan != null
        && plan.scope() != dev.workbench.framework.workflow.plan.WorkflowTemplate.Scope.TEXT_ONLY)
      result.add(
          new ActionView(
              handover != null && "ACTIVE".equals(handover.status())
                  ? "RETURN_HANDOVER"
                  : "TAKE_OVER",
              handover != null && "ACTIVE".equals(handover.status()) ? "交还人工接手" : "人工接手",
              true,
              null,
              runId,
              null,
              null,
              handover != null && handover.evidenceInvalidated() ? "代码已变化，旧测试与审批失效" : "当前暂停运行"));
    if (status.canCancel())
      result.add(new ActionView("CANCEL", "取消运行", true, null, runId, null, null, "当前运行"));
    if (status == RunStatus.INTERRUPTED)
      for (var checkpoint : checkpoints)
        result.add(
            new ActionView(
                "RECOVER",
                checkpoint.available() ? "从检查点恢复" : "该检查点不可用",
                checkpoint.available(),
                checkpoint.available() ? null : "来源结果、文件或运行状态已变化，服务端拒绝恢复。",
                runId,
                plan == null ? null : plan.id().toString(),
                checkpoint.id().toString(),
                checkpoint.completedNodes() == 0
                    ? "首个步骤启动前"
                    : "完成 " + checkpoint.completedNodes() + " 步后"));
    return List.copyOf(result);
  }

  private boolean pendingPauseRequest(String runId) {
    var latest =
        events
            .findFirstByRunIdAndEventTypeInOrderByIdDesc(
                runId, List.of("PAUSE_REQUESTED", "PAUSED", "RESUMED"))
            .map(event -> EventType.valueOf(event.getEventType()));
    return latest
        .map(type -> type == EventType.PAUSE_REQUESTED || type == EventType.PAUSED)
        .orElse(false);
  }

  List<WorkflowCheckpoint> recoveryCheckpoints(String runId, WorkflowPlan plan) {
    if (plan == null) return List.of();
    try {
      return recovery.checkpoints(runId, plan);
    } catch (RuntimeException e) {
      // 历史运行缺少检查点依赖数据时不阻断流程图；恢复入口显示为未记录。
      return List.of();
    }
  }

  /** 该运行最新已提交事件的序号；诊断游标进度使用，不作为读取入口。 */
  long lastEventSeq(String runId) {
    return events.maxRunSeq(runId);
  }
}
