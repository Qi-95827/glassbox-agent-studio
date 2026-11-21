package dev.workbench.delivery;

import static dev.workbench.delivery.ExecutionViewSupport.nullable;
import static dev.workbench.delivery.ExecutionViewSupport.settled;

import dev.workbench.delivery.ExecutionViewService.ActionView;
import dev.workbench.delivery.ExecutionViewService.BindingComponentView;
import dev.workbench.delivery.ExecutionViewService.DependencyView;
import dev.workbench.delivery.ExecutionViewService.EdgeView;
import dev.workbench.delivery.ExecutionViewService.ExecutionView;
import dev.workbench.delivery.ExecutionViewService.FocusItem;
import dev.workbench.delivery.ExecutionViewService.FocusView;
import dev.workbench.delivery.ExecutionViewService.NodeView;
import dev.workbench.delivery.ExecutionViewService.PlanView;
import dev.workbench.domain.InvocationStatus;
import dev.workbench.framework.workflow.check.SystemCheckStatus;
import dev.workbench.framework.workflow.plan.PlanNode;
import dev.workbench.framework.workflow.plan.PlanNodeStatus;
import dev.workbench.framework.workflow.plan.WorkflowAttempt;
import dev.workbench.framework.workflow.plan.WorkflowCheck;
import dev.workbench.framework.workflow.plan.WorkflowPlan;
import dev.workbench.planning.PlanService;
import dev.workbench.store.AgentAssemblyRecordService;
import dev.workbench.store.RunStateStore;
import dev.workbench.store.TaskRecordService;
import dev.workbench.orchestration.TaskSupplementService;
import dev.workbench.task.WorkflowExecutionService;
import dev.workbench.workspace.RunHandoverService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** 执行图的只读投影：流程、节点、依赖、焦点、阻塞与可用操作。 */
@Component
final class ExecutionGraphProjector {
  private final RunStateStore runs;
  private final AgentAssemblyRecordService agents;
  private final TaskRecordService tasks;
  private final PlanService plans;
  private final WorkflowExecutionService workflows;
  private final TaskSupplementService supplements;
  private final RunHandoverService handovers;
  private final dev.workbench.ownership.OwnershipService ownership;
  private final com.fasterxml.jackson.databind.ObjectMapper json;
  private final ExecutionOperationProjector operations;
  private final ExecutionSummaryProjector summaries;

  ExecutionGraphProjector(
      RunStateStore runs,
      AgentAssemblyRecordService agents,
      TaskRecordService tasks,
      PlanService plans,
      WorkflowExecutionService workflows,
      TaskSupplementService supplements,
      RunHandoverService handovers,
      dev.workbench.ownership.OwnershipService ownership,
      com.fasterxml.jackson.databind.ObjectMapper json,
      ExecutionOperationProjector operations,
      ExecutionSummaryProjector summaries) {
    this.runs = runs;
    this.agents = agents;
    this.tasks = tasks;
    this.plans = plans;
    this.workflows = workflows;
    this.supplements = supplements;
    this.handovers = handovers;
    this.ownership = ownership;
    this.json = json;
    this.operations = operations;
    this.summaries = summaries;
  }

  ExecutionView view(String runId) {
    ownership.requireRun(runId);
    var run = runs.run(runId);
    var snapshot = runs.runSnapshot(runId);
    var plan = plans.workflowPlan(runId);
    var nodes = plans.find(runId);
    var attempts = workflows.workflowAttempts(runId);
    var checks = workflows.workflowChecks(runId);
    var models = agents.models(runId);
    var tools = agents.tools(runId);
    var byNodeAttempts = ExecutionViewSupport.groupAttempts(attempts);
    var byNodeChecks = ExecutionViewSupport.groupChecks(checks);
    var nodeViews = new ArrayList<NodeView>();
    for (var node : nodes) nodeViews.add(nodeView(node, nodes, byNodeAttempts, byNodeChecks));
    var blocking = operations.blocking(snapshot.status(), plan, nodes, byNodeChecks);
    var focus = new FocusView(focus(nodes, byNodeAttempts, byNodeChecks, models, tools));
    var handover = handovers.findByRun(runId);
    boolean revalidationRequired = handovers.requiresRevalidation(runId);
    var actions =
        new ArrayList<>(
            operations.actions(
                snapshot.status(),
                runId,
                plan,
                nodes,
                operations.recoveryCheckpoints(runId, plan),
                handover,
                revalidationRequired));
    int taskVersion = tasks.taskVersion(snapshot.taskId().toString());
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
    var counts = summaries.counts(nodes, checks, models, tools);
    var budget = summaries.budgetView(snapshot, runId);
    var recoverySupport = summaries.recoverySupportView(snapshot, plan, runId);
    return new ExecutionView(
        ExecutionViewService.SCHEMA_VERSION,
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
        summaries.recoveryView(runId, run, plan, nodes, models.size(), tools.size()),
        summaries.supplementView(supplementRequest, nodes),
        summaries.handoverView(handover, revalidationRequired),
        counts,
        budget,
        recoverySupport,
        operations.lastEventSeq(runId));
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

  NodeView nodeView(
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
            ExecutionViewSupport.latestCheck(checks.getOrDefault(node.id(), List.of()))
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

  List<DependencyView> dependencies(List<PlanNode> nodes, PlanNode node) {
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
}
