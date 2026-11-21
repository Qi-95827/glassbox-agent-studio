package dev.workbench.task;

import dev.workbench.domain.ToolKind;
import dev.workbench.framework.tool.ResourceVersion;
import dev.workbench.framework.workflow.plan.PlanNode;
import dev.workbench.planning.PlanService;
import dev.workbench.framework.workflow.plan.WorkflowHandoff;
import dev.workbench.workspace.WorkspaceAuthorizationService;
import dev.workbench.workspace.WorkspaceService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import dev.workbench.store.AgentAssemblyRecordService;
import dev.workbench.store.RunStateStore;
import dev.workbench.store.TaskRecordService;
import dev.workbench.store.ReviewCompletion;
import dev.workbench.store.StopCompletion;

/**
 * 运行级交付投影：按已结算节点汇总文本结果、实际写入文件、审查收到的交接和检查结论。
 *
 * <p>文件只来自该节点成功写入回执中的资源版本，不取工作区当前差异；读取文件正文时单独核对 当前 SHA-256，变化后保留历史回执并标为过期。模型结论不被当作测试或人工验收。
 */
@Service
public class RunDeliveryService {
  private final PlanService plans;
  private final RunStateStore runs;
  private final TaskRecordService tasks;
  private final AgentAssemblyRecordService agents;
  private final WorkspaceService workspaces;
  private final WorkspaceAuthorizationService workspaceAuthorization;
  private final WorkflowExecutionService workflowRuns;
  private final dev.workbench.ownership.OwnershipService ownership;

  public RunDeliveryService(
      PlanService plans,
      RunStateStore runs,
      TaskRecordService tasks,
      AgentAssemblyRecordService agents,
      WorkspaceService workspaces,
      WorkspaceAuthorizationService workspaceAuthorization,
      WorkflowExecutionService workflowRuns,
      dev.workbench.ownership.OwnershipService ownership) {
    this.plans = plans;
    this.runs = runs;
    this.tasks = tasks;
    this.agents = agents;
    this.workspaces = workspaces;
    this.workspaceAuthorization = workspaceAuthorization;
    this.workflowRuns = workflowRuns;
    this.ownership = ownership;
  }

  public record Delivery(
      String runId,
      String taskId,
      String status,
      String reason,
      String result,
      List<NodeDelivery> nodes,
      List<FileDelivery> files,
      List<CheckDelivery> checks,
      List<String> unverified) {}

  public record NodeDelivery(
      String nodeId,
      String key,
      String label,
      String role,
      String status,
      String result,
      List<FileDelivery> files,
      List<String> missingRequiredFiles,
      List<HandoffDelivery> inbound,
      List<HandoffDelivery> outbound) {}

  public record FileDelivery(
      String path,
      String sha256,
      String toolCallId,
      String nodeId,
      String nodeKey,
      String nodeLabel,
      String currentSha256,
      boolean currentMatches) {}

  public record HandoffDelivery(
      String attemptId, String fromNodeId, String fromLabel, String toNodeId, String toLabel) {}

  public record CheckDelivery(
      String checkId,
      String nodeId,
      String nodeLabel,
      String status,
      String summary,
      String evidence,
      boolean evidenceCurrent) {}

  public Delivery delivery(String runId) {
    ownership.requireRun(runId);
    var run = runs.runSnapshot(runId);
    var runRow = runs.run(runId);
    var task = tasks.taskDefinition(run.taskId().toString());
    var nodes = plans.find(runId);
    var specs = plans.specsByNode(runId);
    var agentRows = new LinkedHashMap<String, Map<String, Object>>();
    for (var agent : agents.agents(runId)) agentRows.put(String.valueOf(agent.get("ID")), agent);
    var toolsByAgent = new LinkedHashMap<String, List<Map<String, Object>>>();
    for (var tool : agents.tools(runId)) {
      if (!ToolKind.WORKSPACE_WRITE.recordName().equals(String.valueOf(tool.get("TOOL_NAME")))
          || !"COMPLETED".equals(String.valueOf(tool.get("STATUS")))
          || tool.get("RESOURCE_VERSION") == null) continue;
      toolsByAgent
          .computeIfAbsent(String.valueOf(tool.get("AGENT_ID")), key -> new ArrayList<>())
          .add(tool);
    }
    var handoffs = workflowRuns.workflowHandoffs(runId);
    var checks = workflowRuns.workflowChecks(runId);
    var nodeById = new LinkedHashMap<UUID, PlanNode>();
    for (var node : nodes) nodeById.put(node.id(), node);
    var deliveries = new ArrayList<NodeDelivery>();
    var files = new ArrayList<FileDelivery>();
    for (var node : nodes) {
      if (node.systemCheck()) continue;
      var spec = specs.get(node.id());
      // 同一节点多次写入同一文件时以最后一次成功回执为准，避免交付列表重复。
      var nodeFiles = new LinkedHashMap<String, FileDelivery>();
      for (var tool :
          toolsByAgent.getOrDefault(
              node.agentId() == null ? "" : node.agentId().toString(), List.of())) {
        for (var entry :
            ResourceVersion.decode(String.valueOf(tool.get("RESOURCE_VERSION"))).entrySet()) {
          nodeFiles.put(
              entry.getKey(),
              file(
                  task.workspaceId(),
                  entry.getKey(),
                  entry.getValue(),
                  String.valueOf(tool.get("ID")),
                  node));
        }
      }
      files.addAll(nodeFiles.values());
      var produced = nodeFiles.keySet();
      var missing =
          spec == null
              ? List.<String>of()
              : spec.requiredFiles().stream().filter(path -> !produced.contains(path)).toList();
      var inbound = new ArrayList<HandoffDelivery>();
      var outbound = new ArrayList<HandoffDelivery>();
      for (var handoff : handoffs) {
        if (node.id().equals(handoff.receiverNodeId()))
          inbound.add(handoff(handoff, nodeById, true));
        if (node.id().equals(handoff.senderNodeId()))
          outbound.add(handoff(handoff, nodeById, false));
      }
      var agent = node.agentId() == null ? null : agentRows.get(node.agentId().toString());
      deliveries.add(
          new NodeDelivery(
              node.id().toString(),
              node.key(),
              node.label(),
              node.role() == null ? null : node.role().name(),
              node.status().name(),
              agent == null ? null : (String) agent.get("RESULT"),
              List.copyOf(nodeFiles.values()),
              List.copyOf(missing),
              List.copyOf(inbound),
              List.copyOf(outbound)));
    }
    var unverified = new ArrayList<String>();
    String reason = runRow.get("REASON") == null ? null : String.valueOf(runRow.get("REASON"));
    String result = runRow.get("RESULT") == null ? null : String.valueOf(runRow.get("RESULT"));
    if (reason != null && !reason.isBlank()) unverified.add(reason);
    for (var item : deliveries)
      if (!item.missingRequiredFiles().isEmpty())
        unverified.add(item.label() + "：缺少必需文件 " + String.join("、", item.missingRequiredFiles()));
    return new Delivery(
        runId,
        run.taskId().toString(),
        run.status().name(),
        reason,
        result,
        List.copyOf(deliveries),
        List.copyOf(files),
        checks.stream()
            .map(
                check ->
                    new CheckDelivery(
                        check.id().toString(),
                        check.nodeId().toString(),
                        label(nodeById, check.nodeId()),
                        check.status().name(),
                        check.summary(),
                        check.evidence(),
                        check.evidenceCurrent() == null || check.evidenceCurrent()))
            .toList(),
        List.copyOf(unverified));
  }

  public record DeliveryFile(
      String path,
      String receiptSha256,
      String currentSha256,
      boolean currentMatches,
      String content,
      String nodeLabel) {}

  /**
   * 运行级交付正文：只允许读取该运行成功写入回执中出现的路径。
   *
   * <p>当前摘要与回执不一致时不返回正文，只返回历史回执和当前摘要，避免把工作区新版本 当成旧运行的交付内容。
   */
  public DeliveryFile file(String runId, String path) {
    ownership.requireRun(runId);
    String normalized = dev.workbench.workspace.AuthorizedFileSet.normalize(path);
    var delivery = delivery(runId);
    var file =
        delivery.files().stream()
            .filter(item -> item.path().equals(normalized))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("该路径不属于此运行的交付文件。"));
    var task = tasks.taskDefinition(runs.runSnapshot(runId).taskId().toString());
    // 读取前复核当前授权：目录撤权后，即使内容未变也不能再读取正文。
    workspaceAuthorization.requireCurrent(task.id().toString());
    var scope = workspaceAuthorization.find(task.id().toString());
    @SuppressWarnings("unchecked")
    var effective =
        scope == null
            ? task.fileScopes()
            : (java.util.List<String>) scope.getOrDefault("EFFECTIVE_FILES", task.fileScopes());
    if (!effective.contains(normalized))
      throw new DeliveryAccessDeniedException("当前授权已撤销该文件，不能读取交付正文。");
    if (task.workspaceId() == null) throw new IllegalStateException("该运行没有可读取正文的工作区。");
    var current = workspaces.read(task.workspaceId(), normalized);
    boolean matches = current.sha256().equals(file.sha256());
    return new DeliveryFile(
        normalized,
        file.sha256(),
        current.sha256(),
        matches,
        matches ? current.content() : null,
        file.nodeLabel());
  }

  private FileDelivery file(
      String workspaceId, String path, String sha256, String toolCallId, PlanNode node) {
    String current = null;
    if (workspaceId != null) {
      try {
        current = workspaces.read(workspaceId, path).sha256();
      } catch (RuntimeException ignored) {
        // 当前文件不可读时保留历史回执，明确标为不一致。
      }
    }
    return new FileDelivery(
        path,
        sha256,
        toolCallId,
        node.id().toString(),
        node.key(),
        node.label(),
        current,
        sha256.equals(current));
  }

  private static HandoffDelivery handoff(
      WorkflowHandoff handoff, Map<UUID, PlanNode> nodes, boolean inbound) {
    var from = nodes.get(handoff.senderNodeId());
    var to = nodes.get(handoff.receiverNodeId());
    return new HandoffDelivery(
        (inbound ? handoff.receiverAttemptId() : handoff.senderAttemptId()).toString(),
        handoff.senderNodeId().toString(),
        from == null ? null : from.label(),
        handoff.receiverNodeId().toString(),
        to == null ? null : to.label());
  }

  private static String label(Map<UUID, PlanNode> nodes, UUID id) {
    var node = nodes.get(id);
    return node == null ? null : node.label();
  }
}
