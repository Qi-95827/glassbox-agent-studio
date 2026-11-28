package dev.workbench.delivery;

import static dev.workbench.delivery.ExecutionViewSupport.integer;
import static dev.workbench.delivery.ExecutionViewSupport.label;
import static dev.workbench.delivery.ExecutionViewSupport.longValue;
import static dev.workbench.delivery.ExecutionViewSupport.nullable;
import static dev.workbench.delivery.ExecutionViewSupport.parseUuid;
import static dev.workbench.delivery.ExecutionViewSupport.safe;

import dev.workbench.delivery.ExecutionViewService.AttemptView;
import dev.workbench.delivery.ExecutionViewService.CheckInputView;
import dev.workbench.delivery.ExecutionViewService.CheckReceiptView;
import dev.workbench.delivery.ExecutionViewService.CheckView;
import dev.workbench.delivery.ExecutionViewService.HandoffView;
import dev.workbench.delivery.ExecutionViewService.ModelCallView;
import dev.workbench.delivery.ExecutionViewService.NodeDetail;
import dev.workbench.delivery.ExecutionViewService.NodeEventView;
import dev.workbench.delivery.ExecutionViewService.ToolCallView;
import dev.workbench.delivery.ExecutionViewService.ToolEvidenceView;
import dev.workbench.framework.workflow.plan.PlanNode;
import dev.workbench.framework.workflow.plan.WorkflowAttempt;
import dev.workbench.framework.workflow.plan.WorkflowCheck;
import dev.workbench.framework.workflow.plan.WorkflowHandoff;
import dev.workbench.persistence.repository.EventRepository;
import dev.workbench.planning.PlanService;
import dev.workbench.store.AgentAssemblyRecordService;
import dev.workbench.store.RunStateStore;
import dev.workbench.task.WorkflowExecutionService;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** 单节点详情与尝试、模型、工具、检查、交接和事件证据投影。 */
@Component
final class ExecutionNodeDetailProjector {
  private final RunStateStore runs;
  private final AgentAssemblyRecordService agents;
  private final PlanService plans;
  private final WorkflowExecutionService workflows;
  private final EventRepository events;
  private final ExecutionGraphProjector graph;
  private final dev.workbench.ownership.OwnershipService ownership;
  private final dev.workbench.store.GlassboxTraceStore traces;
  private final dev.workbench.framework.agent.definition.AgentDefinitionCatalog definitions;

  ExecutionNodeDetailProjector(
      RunStateStore runs,
      AgentAssemblyRecordService agents,
      PlanService plans,
      WorkflowExecutionService workflows,
      EventRepository events,
      ExecutionGraphProjector graph,
      dev.workbench.ownership.OwnershipService ownership,
      dev.workbench.store.GlassboxTraceStore traces,
      dev.workbench.framework.agent.definition.AgentDefinitionCatalog definitions) {
    this.runs = runs;
    this.agents = agents;
    this.plans = plans;
    this.workflows = workflows;
    this.events = events;
    this.graph = graph;
    this.ownership = ownership;
    this.traces = traces;
    this.definitions = definitions;
  }

  NodeDetail detail(String runId, String nodeIdText) {
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
    var models = agents.models(runId);
    var tools = agents.tools(runId);
    var agentRows = agents.agents(runId);
    var assemblies = agents.agentAssemblies(runId);
    var handoffs = workflows.workflowHandoffs(runId);
    // 单节点详情只下推本节点实例产生的载荷：先按实例 ID 收敛调用 ID，再精确查询，
    // 绝不再为单个节点拉取同一次运行其他节点的 64KB 级正文。
    var nodeAgentIds =
        attempts.stream().map(attempt -> attempt.agentId().toString()).collect(java.util.stream.Collectors.toSet());
    var nodeModelCallIds = new java.util.LinkedHashSet<String>();
    var nodeToolCallIds = new java.util.LinkedHashSet<String>();
    for (var model : models) {
      if (nodeAgentIds.contains(String.valueOf(model.get("AGENT_ID")))
          && model.get("ID") != null) nodeModelCallIds.add(String.valueOf(model.get("ID")));
    }
    for (var tool : tools) {
      if (nodeAgentIds.contains(String.valueOf(tool.get("AGENT_ID")))
          && tool.get("ID") != null) nodeToolCallIds.add(String.valueOf(tool.get("ID")));
    }
    var modelPayloads = traces.modelPayloadsByIds(runId, nodeModelCallIds);
    var toolTraces = traces.toolTracesByIds(runId, nodeToolCallIds);
    return new NodeDetail(
        ExecutionViewService.SCHEMA_VERSION,
        Instant.now().toString(),
        runId,
        graph.nodeView(
            node,
            nodes,
            ExecutionViewSupport.groupAttempts(allAttempts),
            ExecutionViewSupport.groupChecks(allChecks)),
        graph.dependencies(nodes, node),
        attempts.stream()
            .map(
                attempt ->
                    attemptView(
                        attempt, models, tools, agentRows, assemblies, modelPayloads, toolTraces))
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
        nodeResult(node, attempts, checks, agentRows),
        toolFunnel(node, attempts, assemblies));
  }

  /**
   * 由最近一次尝试的装配快照计算真实工具漏斗；没有装配记录时返回 null，前端只展示「无装配证据」而不是猜裁剪。
   */
  private ExecutionViewService.ToolFunnelView toolFunnel(
      PlanNode node,
      List<WorkflowAttempt> attempts,
      List<dev.workbench.framework.agent.assembly.AgentAssemblySnapshot.Recorded> assemblies) {
    // 与前端一致：按 attemptNo 降序找最近一次「拥有装配快照」的尝试，而不是只看最大 attemptNo。
    var latest = latestAttemptWithAssembly(attempts, assemblies);
    if (latest == null) return null;
    var snapshot =
        assemblies.stream()
            .filter(recorded -> recorded.agentId().equals(latest.agentId()))
            .map(dev.workbench.framework.agent.assembly.AgentAssemblySnapshot.Recorded::snapshot)
            .findFirst()
            .orElse(null);
    if (snapshot == null) return null;
    var definition = definitions.resolve(snapshot.components().definition());
    return ToolFunnelProjector.project(node, snapshot, definition, latest.number());
  }

  /** 三端统一口径：最近一次拥有装配快照的尝试（按 number 降序），与前端 latestAssemblyAttempt 一致。 */
  static WorkflowAttempt latestAttemptWithAssembly(
      List<WorkflowAttempt> attempts,
      List<dev.workbench.framework.agent.assembly.AgentAssemblySnapshot.Recorded> assemblies) {
    if (attempts == null || assemblies == null) return null;
    return attempts.stream()
        .filter(
            attempt ->
                assemblies.stream().anyMatch(recorded -> recorded.agentId().equals(attempt.agentId())))
        .max(java.util.Comparator.comparingInt(WorkflowAttempt::number))
        .orElse(null);
  }

  private AttemptView attemptView(
      WorkflowAttempt attempt,
      List<Map<String, Object>> models,
      List<Map<String, Object>> tools,
      List<Map<String, Object>> agents,
      List<dev.workbench.framework.agent.assembly.AgentAssemblySnapshot.Recorded> assemblies,
      Map<String, dev.workbench.framework.trace.GlassboxModels.ModelCallPayload> modelPayloads,
      Map<String, dev.workbench.framework.trace.GlassboxModels.ToolCallTrace> toolTraces) {
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
            .map(row -> modelView(row, modelPayloads))
            .toList(),
        tools.stream()
            .filter(row -> agentId.equals(nullable(row.get("AGENT_ID"))))
            .map(row -> toolView(row, toolTraces))
            .toList(),
        result,
        assemblies.stream()
            .filter(recorded -> recorded.agentId().toString().equals(agentId))
            .map(dev.workbench.framework.agent.assembly.AgentAssemblySnapshot.Recorded::snapshot)
            .findFirst()
            .orElse(null));
  }

  private ModelCallView modelView(
      Map<String, Object> row,
      Map<String, dev.workbench.framework.trace.GlassboxModels.ModelCallPayload> payloads) {
    String id = nullable(row.get("ID"));
    return new ModelCallView(
        id,
        nullable(row.get("MODEL_NAME")),
        nullable(row.get("STATUS")),
        integer(row.get("INPUT_TOKENS")),
        integer(row.get("OUTPUT_TOKENS")),
        longValue(row.get("DURATION_MS")),
        nullable(row.get("CREATED_AT")),
        nullable(row.get("UPDATED_AT")),
        nullable(row.get("ERROR_MESSAGE")),
        id == null ? null : payloads.get(id));
  }

  private ToolCallView toolView(
      Map<String, Object> row,
      Map<String, dev.workbench.framework.trace.GlassboxModels.ToolCallTrace> traces) {
    String id = nullable(row.get("ID"));
    return new ToolCallView(
        id,
        nullable(row.get("TOOL_NAME")),
        integer(row.get("TOOL_VERSION")),
        nullable(row.get("STATUS")),
        longValue(row.get("DURATION_MS")),
        nullable(row.get("CREATED_AT")),
        nullable(row.get("UPDATED_AT")),
        nullable(row.get("RESULT")),
        nullable(row.get("ERROR_MESSAGE")),
        nullable(row.get("RESOURCE_VERSION")),
        id == null ? null : traces.get(id));
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
        handoff.dependencyNodeId() == null ? null : handoff.dependencyNodeId().toString(),
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
            .toList(),
        dev.workbench.framework.trace.StructuredHandoffExtractor.extract(handoff));
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
}
