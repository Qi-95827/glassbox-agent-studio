package dev.workbench.framework.workflow.view;

import dev.workbench.domain.EventType;
import dev.workbench.domain.InvocationStatus;
import dev.workbench.domain.RunSnapshot;
import dev.workbench.domain.RunStatus;
import dev.workbench.framework.workflow.check.SystemCheckStatus;
import dev.workbench.framework.workflow.plan.PlanNode;
import dev.workbench.framework.workflow.plan.PlanNodeStatus;
import dev.workbench.framework.workflow.plan.PlanService;
import dev.workbench.framework.workflow.plan.WorkflowAttempt;
import dev.workbench.framework.workflow.plan.WorkflowCheck;
import dev.workbench.framework.workflow.plan.WorkflowCheckpoint;
import dev.workbench.framework.workflow.plan.WorkflowHandoff;
import dev.workbench.framework.workflow.plan.WorkflowPlan;
import dev.workbench.persistence.repository.EventRepository;
import dev.workbench.persistence.repository.SupplementRepository;
import dev.workbench.task.RunRecordService;
import dev.workbench.task.TaskSupplementService;
import dev.workbench.task.WorkflowExecutionService;
import dev.workbench.task.WorkflowRecoveryService;
import dev.workbench.workspace.RunHandoverService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 任务 1 的只读执行视图投影。
 *
 * <p>数据全部来自已持久化的计划、节点、尝试、检查、调用和事件。这里只做归属关联与状态汇总， 不根据模板、角色、节点顺序或消息文本补造事实；旧运行缺失的字段保持空值。
 */
@Service
public class ExecutionViewService {
  public static final int SCHEMA_VERSION = 1;

  private final RunRecordService runs;
  private final PlanService plans;
  private final WorkflowExecutionService workflows;
  private final WorkflowRecoveryService recovery;
  private final TaskSupplementService supplements;
  private final RunHandoverService handovers;
  private final EventRepository events;
  private final dev.workbench.ownership.OwnershipService ownership;
  private final com.fasterxml.jackson.databind.ObjectMapper json;

  public ExecutionViewService(
      RunRecordService runs,
      PlanService plans,
      WorkflowExecutionService workflows,
      WorkflowRecoveryService recovery,
      TaskSupplementService supplements,
      RunHandoverService handovers,
      EventRepository events,
      dev.workbench.ownership.OwnershipService ownership,
      com.fasterxml.jackson.databind.ObjectMapper json) {
    this.runs = runs;
    this.plans = plans;
    this.workflows = workflows;
    this.recovery = recovery;
    this.supplements = supplements;
    this.handovers = handovers;
    this.events = events;
    this.ownership = ownership;
    this.json = json;
  }

  public ExecutionView view(String runId) {
    ownership.requireRun(runId);
    var run = runs.run(runId);
    var snapshot = runs.runSnapshot(runId);
    var plan = plans.workflowPlan(runId);
    var nodes = plans.find(runId);
    var attempts = workflows.workflowAttempts(runId);
    var checks = workflows.workflowChecks(runId);
    var models = runs.models(runId);
    var tools = runs.tools(runId);
    var byNodeAttempts = groupAttempts(attempts);
    var byNodeChecks = groupChecks(checks);
    var nodeViews = new ArrayList<NodeView>();
    for (var node : nodes) nodeViews.add(nodeView(node, nodes, byNodeAttempts, byNodeChecks));
    var blocking = blocking(snapshot.status(), plan, nodes, byNodeChecks);
    var focus = new FocusView(focus(nodes, byNodeAttempts, byNodeChecks, models, tools));
    var handover = handovers.findByRun(runId);
    boolean revalidationRequired = handovers.requiresRevalidation(runId);
    var actions =
        new ArrayList<>(
            actions(
                snapshot.status(),
                runId,
                plan,
                nodes,
                recoveryCheckpoints(runId, plan),
                handover,
                revalidationRequired));
    int taskVersion = runs.taskVersion(snapshot.taskId().toString());
    var supplementRequest = supplements.latestRequest(runId);
    if (supplementRequest != null && "OPEN".equals(supplementRequest.status()))
      actions.add(
          new ActionView(
              "SUBMIT_SUPPLEMENT",
              "提交补充信息",
              true,
              null,
              runId,
              plan == null ? null : plan.id().toString(),
              supplementRequest.id(),
              "任务 v" + taskVersion));
    var counts = counts(nodes, checks, models, tools);
    var budget = budgetView(snapshot, runId);
    var recoverySupport = recoverySupportView(snapshot, plan, runId);
    return new ExecutionView(
        SCHEMA_VERSION,
        Instant.now().toString(),
        snapshot.taskId().toString(),
        runId,
        nullable(run.get("PARENT_ID")),
        snapshot.status().name(),
        nullable(run.get("STOP_CAUSE")),
        nullable(run.get("REASON")),
        nullable(run.get("RESULT")),
        nullable(run.get("CREATED_AT")),
        nullable(run.get("UPDATED_AT")),
        snapshot.elapsedMs(),
        snapshot.rounds(),
        snapshot.tools(),
        taskVersion,
        planView(plan),
        List.copyOf(nodeViews),
        edges(nodes),
        focus,
        blocking,
        List.copyOf(actions),
        recoveryView(runId, run, plan, nodes, models.size(), tools.size()),
        supplementView(supplementRequest, nodes),
        handoverView(handover, revalidationRequired),
        counts,
        budget,
        recoverySupport,
        lastEventSeq(runId));
  }

  public NodeDetail detail(String runId, String nodeIdText) {
    ownership.requireRun(runId);
    runs.runSnapshot(runId);
    UUID nodeId = parseUuid(nodeIdText, "节点标识格式无效");
    var plan = plans.workflowPlan(runId);
    var nodes = plans.find(runId);
    var node =
        nodes.stream()
            .filter(candidate -> candidate.id().equals(nodeId))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("节点不属于该运行。"));
    var allAttempts = workflows.workflowAttempts(runId);
    var allChecks = workflows.workflowChecks(runId);
    var attempts = allAttempts.stream().filter(attempt -> attempt.nodeId().equals(nodeId)).toList();
    var checks = allChecks.stream().filter(check -> check.nodeId().equals(nodeId)).toList();
    var models = runs.models(runId);
    var tools = runs.tools(runId);
    var agents = runs.agents(runId);
    var assemblies = runs.agentAssemblies(runId);
    var handoffs = workflows.workflowHandoffs(runId);
    return new NodeDetail(
        SCHEMA_VERSION,
        Instant.now().toString(),
        runId,
        nodeView(node, nodes, groupAttempts(allAttempts), groupChecks(allChecks)),
        dependencies(nodes, node),
        attempts.stream()
            .map(attempt -> attemptView(attempt, models, tools, agents, assemblies))
            .toList(),
        checks.stream().map(this::checkView).toList(),
        handoffs.stream()
            .filter(handoff -> nodeId.equals(handoff.receiverNodeId()))
            .map(handoff -> handoffView(handoff, nodes))
            .toList(),
        handoffs.stream()
            .filter(handoff -> nodeId.equals(handoff.senderNodeId()))
            .map(handoff -> handoffView(handoff, nodes))
            .toList(),
        workflows.workflowCheckReceipts(runId, nodeId).stream()
            .map(
                receipt ->
                    new CheckReceiptView(
                        receipt.checkId(),
                        receipt.dependencyNodeId(),
                        receipt.dependencyNodeLabel(),
                        receipt.receiverNodeId(),
                        receipt.receiverNodeLabel(),
                        receipt.receiverAttemptId(),
                        receipt.acceptedAt(),
                        receipt.summary(),
                        receipt.evidence()))
            .toList(),
        nodeEvents(runId, nodeId),
        nodeResult(node, attempts, checks, agents));
  }

  private PlanView planView(WorkflowPlan plan) {
    if (plan == null) return null;
    return new PlanView(
        plan.id().toString(),
        plan.version(),
        plan.templateId(),
        plan.templateVersion(),
        plan.scope().name(),
        plan.reviewRequired(),
        plan.approvedAt() == null ? null : plan.approvedAt().toString(),
        plan.sourceCheckpointId() == null ? null : plan.sourceCheckpointId().toString(),
        plan.graphSha256(),
        plan.maxAttempts());
  }

  private NodeView nodeView(
      PlanNode node,
      List<PlanNode> all,
      Map<UUID, List<WorkflowAttempt>> attempts,
      Map<UUID, List<WorkflowCheck>> checks) {
    var waiting =
        node.dependencyIds().stream()
            .filter(
                id ->
                    all.stream()
                        .filter(candidate -> candidate.id().equals(id))
                        .findFirst()
                        .map(candidate -> !settled(candidate.status()))
                        .orElse(true))
            .map(UUID::toString)
            .toList();
    boolean ready = node.status() == PlanNodeStatus.PENDING && waiting.isEmpty();
    return new NodeView(
        node.id().toString(),
        node.planVersionId() == null ? null : node.planVersionId().toString(),
        node.sequence(),
        node.key(),
        node.label(),
        node.kind().name(),
        node.phase(),
        node.role() == null ? null : node.role().name(),
        node.status().name(),
        node.agentId() == null ? null : node.agentId().toString(),
        node.sourceAgentId() == null ? null : node.sourceAgentId().toString(),
        node.startedAt() == null ? null : node.startedAt().toString(),
        node.finishedAt() == null ? null : node.finishedAt().toString(),
        node.workspaceRead(),
        node.workspaceWrite(),
        node.budget() == null ? null : node.budget().maxModelCalls(),
        node.budget() == null ? null : node.budget().maxToolCalls(),
        node.definitionRef() == null ? null : node.definitionRef().id(),
        node.definitionRef() == null ? null : node.definitionRef().version(),
        node.checkRef() == null ? null : node.checkRef().id(),
        node.checkRef() == null ? null : node.checkRef().version(),
        node.dependencyIds().stream().map(UUID::toString).toList(),
        ready,
        waiting,
        attempts.getOrDefault(node.id(), List.of()).size(),
        checks.getOrDefault(node.id(), List.of()).size(),
        node.agentBinding() == null ? null : node.agentBinding().kind(),
        node.agentBinding() == null ? null : node.agentBinding().name(),
        node.agentBinding() == null ? null : node.agentBinding().source(),
        node.agentBinding() == null ? null : node.agentBinding().reason(),
        node.agentBinding() == null ? null : node.agentBinding().specSha256(),
        bindingComponents(node, "skills"),
        bindingComponents(node, "tools"));
  }

  /** 从已保存的组件快照展示 Skill/工具版本；损坏时返回空而不是补造记录。 */
  private List<BindingComponentView> bindingComponents(PlanNode node, String field) {
    if (node.agentBinding() == null || node.agentBinding().componentsJson() == null)
      return List.of();
    try {
      var tree = json.readTree(node.agentBinding().componentsJson()).path(field);
      var result = new ArrayList<BindingComponentView>();
      for (var item : tree) {
        result.add(
            new BindingComponentView(
                item.path("id").asText(null),
                item.path("version").asInt(0),
                item.path("name").asText(null),
                item.path("kind").asText(null),
                item.path("sha256").asText(null)));
      }
      return List.copyOf(result);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      return List.of();
    }
  }

  private List<EdgeView> edges(List<PlanNode> nodes) {
    var result = new ArrayList<EdgeView>();
    for (var node : nodes)
      for (var dependency : node.dependencyIds().stream().sorted().toList())
        result.add(new EdgeView(dependency.toString(), node.id().toString()));
    return List.copyOf(result);
  }

  private Map<UUID, List<WorkflowAttempt>> groupAttempts(List<WorkflowAttempt> attempts) {
    var result = new LinkedHashMap<UUID, List<WorkflowAttempt>>();
    for (var attempt : attempts)
      result.computeIfAbsent(attempt.nodeId(), key -> new ArrayList<>()).add(attempt);
    return result;
  }

  private Map<UUID, List<WorkflowCheck>> groupChecks(List<WorkflowCheck> checks) {
    var result = new LinkedHashMap<UUID, List<WorkflowCheck>>();
    for (var check : checks)
      result.computeIfAbsent(check.nodeId(), key -> new ArrayList<>()).add(check);
    return result;
  }

  private List<FocusItem> focus(
      List<PlanNode> nodes,
      Map<UUID, List<WorkflowAttempt>> attempts,
      Map<UUID, List<WorkflowCheck>> checks,
      List<Map<String, Object>> models,
      List<Map<String, Object>> tools) {
    var result = new ArrayList<FocusItem>();
    for (var node :
        nodes.stream().filter(item -> item.status() == PlanNodeStatus.RUNNING).toList()) {
      if (node.systemCheck()) {
        var latest =
            latestCheck(checks.getOrDefault(node.id(), List.of()))
                .filter(check -> check.status() == SystemCheckStatus.RUNNING);
        latest.ifPresent(
            check ->
                result.add(
                    new FocusItem(
                        node.id().toString(),
                        node.label(),
                        null,
                        check.id().toString(),
                        null,
                        "CHECK",
                        check.id().toString(),
                        check.check().id(),
                        check.startedAt().toString(),
                        check.status().name())));
        continue;
      }
      var attempt =
          attempts.getOrDefault(node.id(), List.of()).stream()
              .filter(item -> item.status() == InvocationStatus.RUNNING)
              .max(Comparator.comparingInt(WorkflowAttempt::number))
              .orElse(null);
      // 兼容流程没有 workflow_attempts；实例标识仍来自计划节点，不能用空尝试跳过。
      String agentId =
          attempt != null
              ? attempt.agentId().toString()
              : node.agentId() == null ? null : node.agentId().toString();
      if (agentId == null) continue;
      String attemptId = attempt == null ? null : attempt.id().toString();
      var model =
          models.stream()
              .filter(row -> agentId.equals(nullable(row.get("AGENT_ID"))))
              .filter(row -> InvocationStatus.RUNNING.name().equals(nullable(row.get("STATUS"))))
              .reduce((first, second) -> second);
      if (model.isPresent()) {
        var row = model.get();
        result.add(
            new FocusItem(
                node.id().toString(),
                node.label(),
                attemptId,
                null,
                agentId,
                "MODEL_CALL",
                nullable(row.get("ID")),
                nullable(row.get("MODEL_NAME")),
                nullable(row.get("CREATED_AT")),
                nullable(row.get("STATUS"))));
        continue;
      }
      var tool =
          tools.stream()
              .filter(row -> agentId.equals(nullable(row.get("AGENT_ID"))))
              .filter(row -> InvocationStatus.RUNNING.name().equals(nullable(row.get("STATUS"))))
              .reduce((first, second) -> second);
      if (tool.isPresent()) {
        var row = tool.get();
        result.add(
            new FocusItem(
                node.id().toString(),
                node.label(),
                attemptId,
                null,
                agentId,
                "TOOL_CALL",
                nullable(row.get("ID")),
                nullable(row.get("TOOL_NAME")),
                nullable(row.get("CREATED_AT")),
                nullable(row.get("STATUS"))));
        continue;
      }
      result.add(
          new FocusItem(
              node.id().toString(),
              node.label(),
              attemptId,
              null,
              agentId,
              "ATTEMPT",
              attemptId == null ? agentId : attemptId,
              "Agent 实例已启动",
              attempt == null
                  ? (node.startedAt() == null ? null : node.startedAt().toString())
                  : (attempt.startedAt() == null ? null : attempt.startedAt().toString()),
              attempt == null ? InvocationStatus.RUNNING.name() : attempt.status().name()));
    }
    return List.copyOf(result);
  }

  private List<BlockingView> blocking(
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

  private List<ActionView> actions(
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

  private List<WorkflowCheckpoint> recoveryCheckpoints(String runId, WorkflowPlan plan) {
    if (plan == null) return List.of();
    try {
      return recovery.checkpoints(runId, plan);
    } catch (RuntimeException e) {
      // 历史运行缺少检查点依赖数据时不阻断流程图；恢复入口显示为未记录。
      return List.of();
    }
  }

  private RecoveryView recoveryView(
      String runId,
      Map<String, Object> run,
      WorkflowPlan plan,
      List<PlanNode> nodes,
      int modelCalls,
      int toolCalls) {
    String parentRunId = nullable(run.get("PARENT_ID"));
    String sourceCheckpoint = plan == null ? null : nullable(plan.sourceCheckpointId());
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

  private SupplementView supplementView(
      SupplementRepository.Request request, List<PlanNode> nodes) {
    if (request == null) return null;
    return new SupplementView(
        request.id(),
        request.status(),
        request.kind(),
        request.reasonCode(),
        request.question(),
        request.nodeId(),
        label(nodes, java.util.UUID.fromString(request.nodeId())),
        request.answerPath(),
        request.answerSha256(),
        request.taskVersion(),
        request.createdAt(),
        request.decidedAt());
  }

  private HandoverView handoverView(
      RunHandoverService.Handover handover, boolean revalidationRequired) {
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

  private BudgetView budgetView(RunSnapshot snapshot, String runId) {
    var tokens = runs.reportedTokens(runId);
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
  private RecoverySupportView recoverySupportView(
      RunSnapshot snapshot, WorkflowPlan plan, String runId) {
    if (snapshot.status() != RunStatus.INTERRUPTED) return null;
    if (runs.hasRecovery(runId)) return new RecoverySupportView(false, "此运行已经发起恢复，请查看关联子运行。");
    var task = runs.taskDefinition(snapshot.taskId().toString());
    if (plan != null) {
      if (plan.sourceCheckpointId() != null) return new RecoverySupportView(false, "恢复子运行不支持再次恢复。");
      if (plan.scope() != dev.workbench.framework.workflow.plan.WorkflowTemplate.Scope.TEXT_ONLY
          && plan.scope()
              != dev.workbench.framework.workflow.plan.WorkflowTemplate.Scope.WORKSPACE_READ_ONLY)
        return new RecoverySupportView(false, "当前写入、Bug 或外部动作流程尚不支持自动恢复。");
      var checkpoints = recoveryCheckpoints(runId, plan);
      if (checkpoints.stream().anyMatch(WorkflowCheckpoint::available))
        return new RecoverySupportView(true, "可从已结算节点边界恢复，预算和复用来源已记录。");
      return new RecoverySupportView(false, "没有可用检查点：节点未结算、文件或授权已变化，或检查点保存被跳过。");
    }
    if (task.writeEnabled() || task.bugId() != null || task.memoryEnabled())
      return new RecoverySupportView(false, "写入、模拟 Bug 或启用项目背景的任务不支持自动恢复。");
    if (task.workspaceId() != null) return new RecoverySupportView(false, "该工作区任务没有可安全恢复的检查点。");
    return new RecoverySupportView(false, "只有支持的分析检查点可以恢复。");
  }

  private CountsView counts(
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

  private List<DependencyView> dependencies(List<PlanNode> nodes, PlanNode node) {
    return node.dependencyIds().stream()
        .map(
            id ->
                nodes.stream()
                    .filter(candidate -> candidate.id().equals(id))
                    .findFirst()
                    .map(
                        dependency ->
                            new DependencyView(
                                dependency.id().toString(),
                                dependency.label(),
                                dependency.role() == null ? null : dependency.role().name(),
                                dependency.status().name(),
                                dependency.kind().name()))
                    .orElse(new DependencyView(id.toString(), null, null, null, null)))
        .toList();
  }

  private AttemptView attemptView(
      WorkflowAttempt attempt,
      List<Map<String, Object>> models,
      List<Map<String, Object>> tools,
      List<Map<String, Object>> agents,
      List<dev.workbench.framework.agent.assembly.AgentAssemblySnapshot.Recorded> assemblies) {
    String agentId = attempt.agentId().toString();
    String result =
        agents.stream()
            .filter(row -> agentId.equals(nullable(row.get("ID"))))
            .map(row -> nullable(row.get("RESULT")))
            .filter(Objects::nonNull)
            .findFirst()
            .orElse(null);
    return new AttemptView(
        attempt.id().toString(),
        attempt.number(),
        agentId,
        attempt.status().name(),
        attempt.startedAt() == null ? null : attempt.startedAt().toString(),
        attempt.updatedAt() == null ? null : attempt.updatedAt().toString(),
        attempt.stopCause() == null ? null : attempt.stopCause().name(),
        models.stream()
            .filter(row -> agentId.equals(nullable(row.get("AGENT_ID"))))
            .map(this::modelView)
            .toList(),
        tools.stream()
            .filter(row -> agentId.equals(nullable(row.get("AGENT_ID"))))
            .map(this::toolView)
            .toList(),
        result,
        assemblies.stream()
            .filter(recorded -> recorded.agentId().toString().equals(agentId))
            .map(dev.workbench.framework.agent.assembly.AgentAssemblySnapshot.Recorded::snapshot)
            .findFirst()
            .orElse(null));
  }

  private ModelCallView modelView(Map<String, Object> row) {
    return new ModelCallView(
        nullable(row.get("ID")),
        nullable(row.get("MODEL_NAME")),
        nullable(row.get("STATUS")),
        integer(row.get("INPUT_TOKENS")),
        integer(row.get("OUTPUT_TOKENS")),
        longValue(row.get("DURATION_MS")),
        nullable(row.get("CREATED_AT")),
        nullable(row.get("UPDATED_AT")),
        nullable(row.get("ERROR_MESSAGE")));
  }

  private ToolCallView toolView(Map<String, Object> row) {
    return new ToolCallView(
        nullable(row.get("ID")),
        nullable(row.get("TOOL_NAME")),
        integer(row.get("TOOL_VERSION")),
        nullable(row.get("STATUS")),
        longValue(row.get("DURATION_MS")),
        nullable(row.get("CREATED_AT")),
        nullable(row.get("UPDATED_AT")),
        nullable(row.get("RESULT")),
        nullable(row.get("ERROR_MESSAGE")),
        nullable(row.get("RESOURCE_VERSION")));
  }

  private CheckView checkView(WorkflowCheck check) {
    return new CheckView(
        check.id().toString(),
        check.attemptNo(),
        check.check().id(),
        check.check().version(),
        check.status().name(),
        check.summary(),
        check.evidence(),
        check.externalEvidenceId(),
        check.evidenceCurrent(),
        check.inputsSha256(),
        check.startedAt() == null ? null : check.startedAt().toString(),
        check.finishedAt() == null ? null : check.finishedAt().toString(),
        check.inputs().stream()
            .map(
                input ->
                    new CheckInputView(
                        input.dependencyNodeId().toString(),
                        input.sourceKind().name(),
                        input.sourceAttemptId() == null ? null : input.sourceAttemptId().toString(),
                        input.sourceCheckId() == null ? null : input.sourceCheckId().toString(),
                        input.sourceRunId().toString(),
                        input.resultSha256(),
                        input.sourceResult()))
            .toList());
  }

  private HandoffView handoffView(WorkflowHandoff handoff, List<PlanNode> nodes) {
    return new HandoffView(
        handoff.version(),
        handoff.senderNodeId().toString(),
        label(nodes, handoff.senderNodeId()),
        handoff.senderAttemptId().toString(),
        handoff.senderAgentId().toString(),
        handoff.receiverNodeId().toString(),
        label(nodes, handoff.receiverNodeId()),
        handoff.receiverAttemptId().toString(),
        handoff.receiverAgentId().toString(),
        handoff.dependencyNodeId().toString(),
        handoff.dependencyNodeId() == null ? null : label(nodes, handoff.dependencyNodeId()),
        handoff.acceptedAt() == null ? null : handoff.acceptedAt().toString(),
        handoff.sourceRunId() == null ? null : handoff.sourceRunId().toString(),
        handoff.sourceResult(),
        handoff.toolEvidence().stream()
            .map(
                evidence ->
                    new ToolEvidenceView(
                        evidence.toolCallId().toString(),
                        evidence.tool().name(),
                        evidence.version(),
                        evidence.resourceVersion()))
            .toList());
  }

  private String nodeResult(
      PlanNode node,
      List<WorkflowAttempt> attempts,
      List<WorkflowCheck> checks,
      List<Map<String, Object>> agents) {
    if (node.systemCheck()) {
      return checks.stream()
          .reduce((first, second) -> second)
          .map(check -> safe(check.summary(), safe(check.evidence(), null)))
          .orElse(null);
    }
    var latest = attempts.stream().max(Comparator.comparingInt(WorkflowAttempt::number));
    if (latest.isEmpty()) return null;
    String agentId = latest.get().agentId().toString();
    return agents.stream()
        .filter(row -> agentId.equals(nullable(row.get("ID"))))
        .map(row -> nullable(row.get("RESULT")))
        .filter(Objects::nonNull)
        .findFirst()
        .orElse(null);
  }

  private List<NodeEventView> nodeEvents(String runId, UUID nodeId) {
    return events
        .findByRunIdAndNodeIdAndRunSeqIsNotNullOrderByRunSeqAsc(runId, nodeId.toString())
        .stream()
        .map(
            event ->
                new NodeEventView(
                    event.getId(),
                    event.getEventType(),
                    event.getMessage(),
                    event.getCreatedAt(),
                    event.getAttemptId(),
                    event.getCheckId(),
                    event.getModelCallId(),
                    event.getToolCallId(),
                    event.getReasonCode()))
        .toList();
  }

  /** 该运行最新已提交事件的序号；诊断游标进度使用，不作为读取入口。 */
  private long lastEventSeq(String runId) {
    return events.maxRunSeq(runId);
  }

  private static java.util.Optional<WorkflowCheck> latestCheck(List<WorkflowCheck> checks) {
    return checks.stream().max(Comparator.comparingInt(WorkflowCheck::attemptNo));
  }

  private static List<String> downstreamPending(List<PlanNode> nodes, UUID start) {
    var result = new LinkedHashSet<String>();
    var queue = new java.util.ArrayDeque<UUID>();
    queue.add(start);
    while (!queue.isEmpty()) {
      UUID current = queue.removeFirst();
      for (var node : nodes) {
        if (!node.dependencyIds().contains(current)) continue;
        if (node.status() == PlanNodeStatus.PENDING || node.status() == PlanNodeStatus.SKIPPED) {
          result.add(node.id().toString());
          queue.add(node.id());
        }
      }
    }
    return List.copyOf(result);
  }

  private static boolean settled(PlanNodeStatus status) {
    return status == PlanNodeStatus.COMPLETED || status == PlanNodeStatus.REUSED;
  }

  private static String statusText(SystemCheckStatus status) {
    return switch (status) {
      case FAILED -> "未通过";
      case BLOCKED -> "无法判定";
      case INTERRUPTED -> "已中断";
      default -> status.name();
    };
  }

  private static String label(List<PlanNode> nodes, UUID id) {
    if (id == null) return null;
    return nodes.stream()
        .filter(node -> node.id().equals(id))
        .map(PlanNode::label)
        .findFirst()
        .orElse(null);
  }

  private static String nullable(Object value) {
    if (value == null) return null;
    String text = value.toString();
    return text.isBlank() ? null : text;
  }

  private static Integer integer(Object value) {
    return value instanceof Number number ? number.intValue() : null;
  }

  private static Long longValue(Object value) {
    return value instanceof Number number ? number.longValue() : null;
  }

  private static String safe(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }

  private static UUID parseUuid(String value, String message) {
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(message, e);
    }
  }

  public record ExecutionView(
      int schemaVersion,
      String generatedAt,
      String taskId,
      String runId,
      String parentRunId,
      String runStatus,
      String stopCause,
      String reason,
      String result,
      String createdAt,
      String updatedAt,
      long elapsedMs,
      int rounds,
      int tools,
      int taskVersion,
      PlanView plan,
      List<NodeView> nodes,
      List<EdgeView> edges,
      FocusView focus,
      List<BlockingView> blocking,
      List<ActionView> availableActions,
      RecoveryView recovery,
      SupplementView supplement,
      HandoverView handover,
      CountsView counts,
      BudgetView budget,
      RecoverySupportView recoverySupport,
      long lastEventSeq) {}

  public record PlanView(
      String id,
      int version,
      String templateId,
      int templateVersion,
      String scope,
      boolean reviewRequired,
      String approvedAt,
      String sourceCheckpointId,
      String graphSha256,
      int maxAttempts) {}

  public record NodeView(
      String id,
      String planVersionId,
      int sequence,
      String key,
      String label,
      String kind,
      String phase,
      String role,
      String status,
      String agentId,
      String sourceAgentId,
      String startedAt,
      String finishedAt,
      boolean workspaceRead,
      boolean workspaceWrite,
      Integer maxModelCalls,
      Integer maxToolCalls,
      String definitionId,
      Integer definitionVersion,
      String checkId,
      Integer checkVersion,
      List<String> dependencyIds,
      boolean ready,
      List<String> waitingForNodeIds,
      int attemptCount,
      int checkCount,
      String agentKind,
      String agentName,
      String agentSource,
      String agentReason,
      String agentSpecSha256,
      List<BindingComponentView> agentSkills,
      List<BindingComponentView> agentTools) {}

  public record BindingComponentView(
      String id, int version, String name, String kind, String sha256) {}

  public record EdgeView(String fromNodeId, String toNodeId) {}

  public record FocusView(List<FocusItem> active) {}

  public record FocusItem(
      String nodeId,
      String nodeLabel,
      String attemptId,
      String checkId,
      String agentId,
      String activityKind,
      String activityId,
      String activityLabel,
      String startedAt,
      String status) {}

  public record BlockingView(
      String code,
      String message,
      String scope,
      String nodeId,
      String nodeLabel,
      List<String> blockingNodeIds) {}

  public record ActionView(
      String actionCode,
      String label,
      boolean enabled,
      String disabledReason,
      String runId,
      String planVersionId,
      String checkpointId,
      String targetLabel) {}

  public record RecoveryView(
      String parentRunId,
      String sourceCheckpointId,
      List<ReusedNodeView> reusedNodes,
      InheritedView inherited,
      long newModelCalls,
      long newToolCalls) {}

  public record ReusedNodeView(String nodeId, String key, String label, String sourceAgentId) {}

  public record InheritedView(Integer rounds, Integer tools, Long elapsedMs) {}

  /** 待补充或已应用的补充请求；不存在时为空。 */
  public record SupplementView(
      String id,
      String status,
      String kind,
      String reasonCode,
      String question,
      String nodeId,
      String nodeLabel,
      String answerPath,
      String answerSha256,
      int taskVersion,
      String createdAt,
      String decidedAt) {}

  /** 人工接手记录；旧测试或审批是否失效以服务端快照为准。 */
  public record HandoverView(
      String id,
      String status,
      String baselineFingerprint,
      String currentFingerprint,
      boolean evidenceInvalidated,
      boolean revalidationRequired,
      String note,
      String takenAt,
      String returnedAt) {}

  public record BudgetView(
      Integer maxRounds,
      int usedRounds,
      Long remainingRounds,
      Integer maxTools,
      int usedTools,
      Long remainingTools,
      Long maxTokens,
      long knownTokens,
      long unknownTokenCalls,
      Long remainingTokens,
      Integer maxSeconds,
      long elapsedMs,
      Long remainingMs,
      boolean inherited) {}

  public record RecoverySupportView(boolean supported, String reason) {}

  public record CountsView(
      int total,
      long completed,
      long reused,
      long running,
      long pending,
      long failed,
      long skipped,
      int checks,
      int modelCalls,
      int toolCalls,
      int unknownTokenCalls) {}

  public record NodeDetail(
      int schemaVersion,
      String generatedAt,
      String runId,
      NodeView node,
      List<DependencyView> dependencies,
      List<AttemptView> attempts,
      List<CheckView> checks,
      List<HandoffView> inboundHandoffs,
      List<HandoffView> outboundHandoffs,
      List<CheckReceiptView> checkReceipts,
      List<NodeEventView> nodeEvents,
      String result) {}

  public record DependencyView(
      String nodeId, String label, String role, String status, String kind) {}

  public record AttemptView(
      String id,
      int number,
      String agentId,
      String status,
      String startedAt,
      String updatedAt,
      String stopCause,
      List<ModelCallView> models,
      List<ToolCallView> tools,
      String result,
      dev.workbench.framework.agent.assembly.AgentAssemblySnapshot assembly) {}

  public record ModelCallView(
      String id,
      String name,
      String status,
      Integer inputTokens,
      Integer outputTokens,
      Long durationMs,
      String createdAt,
      String updatedAt,
      String errorMessage) {}

  public record ToolCallView(
      String id,
      String name,
      Integer version,
      String status,
      Long durationMs,
      String createdAt,
      String updatedAt,
      String result,
      String errorMessage,
      String resourceVersion) {}

  public record CheckView(
      String id,
      int attemptNo,
      String checkId,
      int checkVersion,
      String status,
      String summary,
      String evidence,
      String externalEvidenceId,
      Boolean evidenceCurrent,
      String inputsSha256,
      String startedAt,
      String finishedAt,
      List<CheckInputView> inputs) {}

  public record CheckInputView(
      String dependencyNodeId,
      String sourceKind,
      String sourceAttemptId,
      String sourceCheckId,
      String sourceRunId,
      String resultSha256,
      String sourceResult) {}

  public record CheckReceiptView(
      String checkId,
      String dependencyNodeId,
      String dependencyNodeLabel,
      String receiverNodeId,
      String receiverNodeLabel,
      String receiverAttemptId,
      String acceptedAt,
      String summary,
      String evidence) {}

  public record HandoffView(
      int version,
      String senderNodeId,
      String senderNodeLabel,
      String senderAttemptId,
      String senderAgentId,
      String receiverNodeId,
      String receiverNodeLabel,
      String receiverAttemptId,
      String receiverAgentId,
      String dependencyNodeId,
      String dependencyNodeLabel,
      String acceptedAt,
      String sourceRunId,
      String sourceResult,
      List<ToolEvidenceView> toolEvidence) {}

  public record ToolEvidenceView(
      String toolCallId, String tool, int version, String resourceVersion) {}

  public record NodeEventView(
      long id,
      String type,
      String message,
      String occurredAt,
      String attemptId,
      String checkId,
      String modelCallId,
      String toolCallId,
      String reasonCode) {}
}
