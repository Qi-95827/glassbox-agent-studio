package dev.workbench.observability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.workbench.domain.TaskStatus;
import dev.workbench.persistence.repository.MonitoringDashboardRepository;
import dev.workbench.persistence.repository.MonitoringDashboardRepository.AssemblyRow;
import dev.workbench.persistence.repository.MonitoringDashboardRepository.EventRow;
import dev.workbench.persistence.repository.MonitoringDashboardRepository.GatewayTraceRow;
import dev.workbench.persistence.repository.MonitoringDashboardRepository.GitActionRow;
import dev.workbench.persistence.repository.MonitoringDashboardRepository.HandoffRow;
import dev.workbench.persistence.repository.MonitoringDashboardRepository.ModelCallRow;
import dev.workbench.persistence.repository.MonitoringDashboardRepository.NodeRow;
import dev.workbench.persistence.repository.MonitoringDashboardRepository.PayloadRow;
import dev.workbench.persistence.repository.MonitoringDashboardRepository.RunRow;
import dev.workbench.persistence.repository.MonitoringDashboardRepository.ToolCallRow;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 单次运行的自动故障诊断。
 *
 * <p>先收集持久化证据，再按“终态 → 工具安检 → 模型 I/O → 交接与装配 → 工作区交付”的标准五步法生成排查向导。
 * 所有快捷路由都指向运行详情页，并通过 nodeId / glassbox 参数直接打开对应节点的宽屏透视 Tab。
 */
@Service
public class RunDiagnosticsService {
  private static final Set<String> TERMINAL_STATUSES =
      Set.of("COMPLETED", "FAILED", "BLOCKED", "LIMITED", "INTERRUPTED", "CANCELLED");
  private static final Set<String> CRITICAL_RUN_STATUSES =
      Set.of("FAILED", "LIMITED", "INTERRUPTED");
  private static final Set<String> PROBLEM_NODE_STATUSES =
      Set.of("FAILED", "LIMITED", "INTERRUPTED");
  private static final long SLOW_MODEL_THRESHOLD_MS = 60_000L;

  private final MonitoringDashboardRepository repository;
  private final ObjectMapper json;

  public RunDiagnosticsService(MonitoringDashboardRepository repository, ObjectMapper json) {
    this.repository = repository;
    this.json = json;
  }

  @Transactional(readOnly = true)
  public RunDiagnostics diagnose(String runId) {
    RunRow run = repository.findRun(runId);
    if (run == null) throw new EmptyResultDataAccessException(1);

    List<String> runIds = List.of(runId);
    List<NodeRow> nodes = repository.nodes(runIds);
    List<ModelCallRow> modelCalls = repository.modelCalls(runIds);
    List<ToolCallRow> toolCalls = repository.toolCalls(runIds);
    List<GatewayTraceRow> traces = repository.gatewayTraces(runIds);
    List<PayloadRow> payloads = repository.modelPayloads(runId);
    List<HandoffRow> handoffs = repository.handoffs(runId);
    List<EventRow> events = repository.recentEvents(runId, 30);
    List<String> agentIds =
        nodes.stream().map(NodeRow::agentId).filter(Objects::nonNull).distinct().toList();
    List<AssemblyRow> assemblies = repository.assemblies(agentIds);
    List<GitActionRow> gitActions = repository.gitActions(run.workspaceId());
    Set<String> deliveredWorkspaces = repository.deliveredWorkspaceIds();

    boolean hasWorkspace = run.workspaceId() != null && !run.workspaceId().isBlank();
    boolean delivered = hasWorkspace && deliveredWorkspaces.contains(run.workspaceId());
    String taskStatus = TaskStatus.derive(run.status(), hasWorkspace, delivered).name();

    Map<String, NodeRow> nodesByAgent = new LinkedHashMap<>();
    Map<String, NodeRow> nodesById = new LinkedHashMap<>();
    for (NodeRow node : nodes) {
      nodesById.put(node.id(), node);
      if (node.agentId() != null) nodesByAgent.put(node.agentId(), node);
    }
    List<NodeRow> problemNodes =
        nodes.stream().filter(node -> PROBLEM_NODE_STATUSES.contains(node.status())).toList();
    List<DenyFact> denies = collectDenies(toolCalls, traces, nodesByAgent);
    List<ModelIssue> modelIssues = collectModelIssues(modelCalls, payloads);
    Set<String> truncatedPayloadIds =
        payloads.stream().filter(PayloadRow::truncated).map(PayloadRow::modelCallId).collect(
            java.util.stream.Collectors.toSet());
    Set<String> grantedTools = grantedTools(assemblies);
    List<NodeRow> missingWriteGrants =
        nodes.stream()
            .filter(NodeRow::workspaceWrite)
            .filter(node -> node.agentId() != null && !grantedTools.contains("WORKSPACE_WRITE"))
            .toList();
    long missingReceipts =
        handoffs.stream().filter(handoff -> handoff.receivedAt() == null).count();

    boolean runTerminalIssue = CRITICAL_RUN_STATUSES.contains(run.status());
    boolean blockedIssue = "BLOCKED".equals(run.status()) && !delivered;
    boolean gitConflict =
        gitActions.stream()
            .anyMatch(
                action ->
                    "CONFLICT".equals(action.status()) || "UNKNOWN".equals(action.status()));
    boolean critical =
        runTerminalIssue
            || blockedIssue
            || !problemNodes.isEmpty()
            || !missingWriteGrants.isEmpty()
            || gitConflict;
    boolean warning =
        !critical
            && (!denies.isEmpty()
                || modelIssues.stream().anyMatch(ModelIssue::failure)
                || modelIssues.stream().anyMatch(ModelIssue::slow)
                || !truncatedPayloadIds.isEmpty()
                || missingReceipts > 0
                || Set.of("BLOCKED", "CANCELLED", "PAUSED").contains(run.status()));
    String health = critical ? RunDiagnostics.CRITICAL : warning ? RunDiagnostics.WARNING : RunDiagnostics.HEALTHY;

    String reasonHint = reasonHint(events, run);
    List<RunDiagnostics.TroubleshootingStep> steps =
        new ArrayList<>(
            List.of(
                terminalStep(run, problemNodes, taskStatus, reasonHint),
                gatewayStep(runId, denies, toolCalls, nodesById, nodesByAgent),
                modelStep(runId, modelCalls, modelIssues, truncatedPayloadIds, nodesByAgent),
                handoffStep(runId, nodes, missingWriteGrants, missingReceipts, handoffs),
                workspaceStep(run, hasWorkspace, delivered, gitActions)));
    String primaryIssue =
        primaryIssue(
            run,
            problemNodes,
            denies,
            modelIssues,
            missingWriteGrants,
            reasonHint,
            delivered,
            gitActions,
            nodesByAgent);

    return new RunDiagnostics(
        run.id(),
        run.taskId(),
        run.taskGoal(),
        run.workspaceId(),
        run.status(),
        taskStatus,
        health,
        primaryIssue,
        List.copyOf(steps),
        Instant.now());
  }

  private RunDiagnostics.TroubleshootingStep terminalStep(
      RunRow run, List<NodeRow> problemNodes, String taskStatus, String reasonHint) {
    String status =
        !problemNodes.isEmpty() || CRITICAL_RUN_STATUSES.contains(run.status())
            ? RunDiagnostics.ISSUE_FOUND
            : Set.of("BLOCKED", "CANCELLED", "PAUSED").contains(run.status())
                ? RunDiagnostics.CHECK_RECOMMENDED
                : RunDiagnostics.LOOKS_OK;
    String finding;
    if (!problemNodes.isEmpty()) {
      finding =
          "运行终态 "
              + run.status()
              + "；异常节点："
              + joinLabels(problemNodes)
              + (reasonHint == null ? "。" : "；最近原因：" + reasonHint + "。");
    } else if (status.equals(RunDiagnostics.CHECK_RECOMMENDED)) {
      finding =
          "运行终态 "
              + run.status()
              + "（任务状态 "
              + taskStatus
              + "）"
              + (reasonHint == null ? "，需确认是否在等待人工处理。" : "；最近原因：" + reasonHint + "。");
    } else if (status.equals(RunDiagnostics.ISSUE_FOUND)) {
      finding =
          "运行终态 "
              + run.status()
              + (run.stopCause() == null ? "" : "，停止原因 " + run.stopCause())
              + (run.reason() == null ? "。" : "；" + run.reason());
    } else {
      finding = "运行终态 " + run.status() + "，未发现失败或阻塞节点。";
    }
    NodeRow target = problemNodes.isEmpty() ? null : problemNodes.getFirst();
    return new RunDiagnostics.TroubleshootingStep(
        1,
        "步骤 1：核对失败/阻塞节点与终态原因",
        status,
        finding,
        target == null ? "查看运行详情" : "查看该节点结论",
        target == null
            ? runRoute(run.id())
            : nodeRoute(run.id(), target.id(), "result"));
  }

  private RunDiagnostics.TroubleshootingStep gatewayStep(
      String runId,
      List<DenyFact> denies,
      List<ToolCallRow> toolCalls,
      Map<String, NodeRow> nodesById,
      Map<String, NodeRow> nodesByAgent) {
    if (!denies.isEmpty()) {
      DenyFact first = denies.getFirst();
      NodeRow target = nodeOf(first.call(), nodesByAgent);
      String gateText =
          first.gateLabel() == null || first.gateLabel().isBlank()
              ? first.gate()
              : first.gateLabel();
      long affectedCalls = denies.stream().map(fact -> fact.call().id()).distinct().count();
      String finding =
          "节点「"
              + labelOf(target)
              + "」的 "
              + first.call().toolName()
              + " 调用在 "
              + gateText
              + "（"
              + first.gate()
              + "）安检门被拦截 "
              + denies.size()
              + " 次，涉及 "
              + affectedCalls
              + " 次工具调用"
              + (first.detail() == null || first.detail().isBlank() ? "。" : "：" + first.detail() + "。");
      return new RunDiagnostics.TroubleshootingStep(
          2,
          "步骤 2：核查 ToolGateway 安全门与工具调用",
          RunDiagnostics.ISSUE_FOUND,
          finding,
          "打开该节点的工具安检门",
          target == null ? runRoute(runId) : nodeRoute(runId, target.id(), "gateway"));
    }
    List<ToolCallRow> failed =
        toolCalls.stream()
            .filter(call -> !"COMPLETED".equals(call.status()) && !"RUNNING".equals(call.status()))
            .toList();
    if (!failed.isEmpty()) {
      NodeRow target = nodeOf(failed.getFirst(), nodesByAgent);
      return new RunDiagnostics.TroubleshootingStep(
          2,
          "步骤 2：核查 ToolGateway 安全门与工具调用",
          RunDiagnostics.CHECK_RECOMMENDED,
          "共有 "
              + failed.size()
              + " 次工具调用未正常完成（如 "
              + failed.getFirst().toolName()
              + "），但未记录到明确的安检 DENY；请核对工具错误摘要与入参。",
          "查看工具安检记录",
          target == null ? runRoute(runId) : nodeRoute(runId, target.id(), "gateway"));
    }
    return new RunDiagnostics.TroubleshootingStep(
        2,
        "步骤 2：核查 ToolGateway 安全门与工具调用",
        RunDiagnostics.LOOKS_OK,
        toolCalls.isEmpty()
            ? "本次运行没有工具调用记录。"
            : "共 " + toolCalls.size() + " 次工具调用，均通过安检门并正常完成。",
        "查看工具安检记录",
        runRoute(runId));
  }

  private RunDiagnostics.TroubleshootingStep modelStep(
      String runId,
      List<ModelCallRow> modelCalls,
      List<ModelIssue> issues,
      Set<String> truncatedPayloadIds,
      Map<String, NodeRow> nodesByAgent) {
    List<ModelIssue> failures = issues.stream().filter(ModelIssue::failure).toList();
    if (!failures.isEmpty()) {
      ModelIssue first = failures.getFirst();
      NodeRow target = nodeOf(first.call(), nodesByAgent);
      return new RunDiagnostics.TroubleshootingStep(
          3,
          "步骤 3：核查模型 I/O、Token 与限流/超时",
          RunDiagnostics.ISSUE_FOUND,
          "模型调用返回 "
              + first.kind()
              + (first.call().errorMessage() == null
                  ? "，可能触发受控停止。"
                  : "：" + first.call().errorMessage()),
          "查看模型 I/O 与错误",
          target == null ? runRoute(runId) : nodeRoute(runId, target.id(), "llm"));
    }
    List<ModelIssue> slow = issues.stream().filter(ModelIssue::slow).toList();
    if (!slow.isEmpty() || !truncatedPayloadIds.isEmpty()) {
      ModelIssue first = slow.isEmpty() ? null : slow.getFirst();
      NodeRow target = first == null ? null : nodeOf(first.call(), nodesByAgent);
      String finding =
          !slow.isEmpty()
              ? "存在 " + slow.size() + " 次超慢模型调用（单次 ≥ 60 秒），建议核对是否触发了服务端超时或长上下文。"
              : "存在 " + truncatedPayloadIds.size() + " 条被截断的模型 I/O 快照，排查长上下文时需结合原始调用记录。";
      return new RunDiagnostics.TroubleshootingStep(
          3,
          "步骤 3：核查模型 I/O、Token 与限流/超时",
          RunDiagnostics.CHECK_RECOMMENDED,
          finding,
          "查看模型 I/O 明细",
          target == null ? runRoute(runId) : nodeRoute(runId, target.id(), "llm"));
    }
    long input =
        modelCalls.stream()
            .mapToLong(call -> call.inputTokens() == null ? 0 : call.inputTokens())
            .sum();
    long output =
        modelCalls.stream()
            .mapToLong(call -> call.outputTokens() == null ? 0 : call.outputTokens())
            .sum();
    NodeRow target = modelCalls.isEmpty() ? null : nodeOf(modelCalls.getFirst(), nodesByAgent);
    return new RunDiagnostics.TroubleshootingStep(
        3,
        "步骤 3：核查模型 I/O、Token 与限流/超时",
        RunDiagnostics.LOOKS_OK,
        modelCalls.isEmpty()
            ? "本次运行没有模型调用记录（可能是纯系统检查或历史数据）。"
            : "共 " + modelCalls.size() + " 次模型调用，均正常返回；已知 Token 输入 "
                + input + " / 输出 " + output + "。",
        "查看模型 I/O 明细",
        target == null ? runRoute(runId) : nodeRoute(runId, target.id(), "llm"));
  }

  private RunDiagnostics.TroubleshootingStep handoffStep(
      String runId,
      List<NodeRow> nodes,
      List<NodeRow> missingWriteGrants,
      long missingReceipts,
      List<HandoffRow> handoffs) {
    if (!missingWriteGrants.isEmpty()) {
      NodeRow target = missingWriteGrants.getFirst();
      return new RunDiagnostics.TroubleshootingStep(
          4,
          "步骤 4：核查上游交接完整性与装配权限漏斗",
          RunDiagnostics.ISSUE_FOUND,
          "节点「"
              + labelOf(target)
              + "」声明需要写入工作区，但实际装配快照未包含 WORKSPACE_WRITE 工具；写入动作会在 ToolGateway 前被权限漏斗拦截。",
          "查看该节点的装配配方",
          nodeRoute(runId, target.id(), "assembly"));
    }
    if (missingReceipts > 0) {
      NodeRow target = nodes.stream().filter(node -> node.agentId() != null).findFirst().orElse(null);
      return new RunDiagnostics.TroubleshootingStep(
          4,
          "步骤 4：核查上游交接完整性与装配权限漏斗",
          RunDiagnostics.CHECK_RECOMMENDED,
          "存在 " + missingReceipts + " 条交接记录没有接收回执；下游可能没有真正接收上游产物，请核对交接信封。",
          "查看交接信封",
          target == null ? runRoute(runId) : nodeRoute(runId, target.id(), "handoff"));
    }
    if (nodes.size() > 1 && handoffs.isEmpty()) {
      return new RunDiagnostics.TroubleshootingStep(
          4,
          "步骤 4：核查上游交接完整性与装配权限漏斗",
          RunDiagnostics.CHECK_RECOMMENDED,
          "该运行包含 " + nodes.size() + " 个节点，但没有交接记录；请确认是否为单节点复用计划或交接证据缺失。",
          "查看节点装配与交接",
          runRoute(runId));
    }
    return new RunDiagnostics.TroubleshootingStep(
        4,
        "步骤 4：核查上游交接完整性与装配权限漏斗",
        RunDiagnostics.LOOKS_OK,
        "写入节点均具备 WORKSPACE_WRITE 装配权限，交接记录与接收回执未发现缺失。",
        "查看节点装配配方",
        runRoute(runId));
  }

  private RunDiagnostics.TroubleshootingStep workspaceStep(
      RunRow run,
      boolean hasWorkspace,
      boolean delivered,
      List<GitActionRow> gitActions) {
    if (!hasWorkspace) {
      return new RunDiagnostics.TroubleshootingStep(
          5,
          "步骤 5：核查工作区差异与 Git 交付状态",
          RunDiagnostics.LOOKS_OK,
          "该任务没有绑定工作区，按纯文本/分析任务处理，无 Git 交付要求。",
          "查看运行交付说明",
          runRoute(run.id()));
    }
    GitActionRow problem =
        gitActions.stream()
            .filter(
                action ->
                    "CONFLICT".equals(action.status()) || "UNKNOWN".equals(action.status()))
            .findFirst()
            .orElse(null);
    if (problem != null) {
      return new RunDiagnostics.TroubleshootingStep(
          5,
          "步骤 5：核查工作区差异与 Git 交付状态",
          RunDiagnostics.ISSUE_FOUND,
          "工作区 Git 动作处于 "
              + problem.status()
              + "（"
              + problem.kind()
              + "）"
              + (problem.detail() == null ? "。" : "：" + problem.detail()),
          "去工作区核对差异与提交状态",
          "/workspaces?id=" + run.workspaceId());
    }
    if (delivered) {
      return new RunDiagnostics.TroubleshootingStep(
          5,
          "步骤 5：核查工作区差异与 Git 交付状态",
          RunDiagnostics.LOOKS_OK,
          "工作区已通过人工 Git 交付确认，任务状态为 "
              + TaskStatus.DELIVERED.name()
              + "。",
          "去工作区查看交付记录",
          "/workspaces?id=" + run.workspaceId());
    }
    if (TERMINAL_STATUSES.contains(run.status()) && !"CANCELLED".equals(run.status())) {
      return new RunDiagnostics.TroubleshootingStep(
          5,
          "步骤 5：核查工作区差异与 Git 交付状态",
          RunDiagnostics.CHECK_RECOMMENDED,
          "运行已结束，但工作区尚未完成 Git 交付确认；请核对未提交差异、版本冲突或待审批项。",
          "去工作区核对差异",
          "/workspaces?id=" + run.workspaceId());
    }
    return new RunDiagnostics.TroubleshootingStep(
        5,
        "步骤 5：核查工作区差异与 Git 交付状态",
        RunDiagnostics.LOOKS_OK,
        "运行仍在进行中，工作区交付将在运行结束后核对。",
        "去工作区查看",
        "/workspaces?id=" + run.workspaceId());
  }

  private String primaryIssue(
      RunRow run,
      List<NodeRow> problemNodes,
      List<DenyFact> denies,
      List<ModelIssue> modelIssues,
      List<NodeRow> missingWriteGrants,
      String reasonHint,
      boolean delivered,
      List<GitActionRow> gitActions,
      Map<String, NodeRow> nodesByAgent) {
    if (!denies.isEmpty()) {
      DenyFact first = denies.getFirst();
      NodeRow target = nodeOf(first.call(), nodesByAgent);
      String gate =
          first.gateLabel() == null || first.gateLabel().isBlank()
              ? first.gate()
              : first.gate() + "（" + first.gateLabel() + "）";
      String effect =
          "WORKSPACE_WRITE".equals(first.call().toolName())
              ? "，导致未能写入目标文件"
              : "，相关工具调用未执行";
      return "节点「"
          + labelOf(target)
          + "」在 "
          + gate
          + " 安检门被拦截 "
          + denies.stream().map(fact -> fact.call().id()).distinct().count()
          + " 次"
          + effect
          + "。";
    }
    List<ModelIssue> failures = modelIssues.stream().filter(ModelIssue::failure).toList();
    if (!failures.isEmpty()) {
      ModelIssue first = failures.getFirst();
      return "模型调用返回 "
          + first.kind()
          + (first.call().errorMessage() == null ? "。" : "：" + first.call().errorMessage());
    }
    if (!problemNodes.isEmpty()) {
      return "节点「"
          + joinLabels(problemNodes)
          + "」执行失败（状态 "
          + problemNodes.getFirst().status()
          + "）"
          + (reasonHint == null ? "。" : "；最近原因：" + reasonHint + "。");
    }
    if ("BLOCKED".equals(run.status()) && !delivered) {
      return run.reason() == null ? "运行已阻塞，等待人工处理。" : "运行已阻塞：" + run.reason();
    }
    if (!missingWriteGrants.isEmpty()) {
      return "节点「"
          + labelOf(missingWriteGrants.getFirst())
          + "」缺少 WORKSPACE_WRITE 装配权限，写入动作无法执行。";
    }
    GitActionRow gitProblem =
        gitActions.stream()
            .filter(
                action ->
                    "CONFLICT".equals(action.status()) || "UNKNOWN".equals(action.status()))
            .findFirst()
            .orElse(null);
    if (gitProblem != null) {
      return "工作区 Git 动作处于 " + gitProblem.status() + "，需要人工核对差异与提交状态。";
    }
    ModelIssue slow = modelIssues.stream().filter(ModelIssue::slow).findFirst().orElse(null);
    if (slow != null) {
      return "检测到超慢模型调用（单次 ≥ 60 秒），建议核对模型服务与上下文规模。";
    }
    return "未发现失败、阻塞、工具拦截或模型异常；该运行健康。";
  }

  private List<DenyFact> collectDenies(
      List<ToolCallRow> toolCalls,
      List<GatewayTraceRow> traces,
      Map<String, NodeRow> nodesByAgent) {
    Map<String, ToolCallRow> callsById = new LinkedHashMap<>();
    for (ToolCallRow call : toolCalls) callsById.put(call.id(), call);
    List<DenyFact> result = new ArrayList<>();
    for (GatewayTraceRow trace : traces) {
      ToolCallRow call = callsById.get(trace.toolCallId());
      if (call == null) continue;
      for (GatewayCheckView check : parseChecks(trace.gatewayChecksJson())) {
        if (!"DENY".equalsIgnoreCase(check.status())) continue;
        result.add(new DenyFact(call, check.gate(), check.label(), check.detail()));
      }
    }
    if (result.isEmpty()) {
      for (ToolCallRow call : toolCalls) {
        if (!"FAILED".equals(call.status()) || call.errorMessage() == null) continue;
        String message = call.errorMessage();
        if (message.contains("工具输入")
            || message.contains("安检")
            || message.contains("白名单")
            || message.contains("授权")
            || message.contains("拒绝")) {
          result.add(new DenyFact(call, "TOOL_GATEWAY", "工具安检门", message));
        }
      }
    }
    return result;
  }

  private List<GatewayCheckView> parseChecks(String raw) {
    if (raw == null || raw.isBlank()) return List.of();
    try {
      JsonNode root = json.readTree(raw);
      if (!root.isArray()) return List.of();
      List<GatewayCheckView> result = new ArrayList<>();
      for (JsonNode node : root) {
        result.add(
            new GatewayCheckView(
                node.path("gate").asText(""),
                node.path("label").asText(""),
                node.path("status").asText(""),
                node.path("detail").asText("")));
      }
      return result;
    } catch (Exception ignored) {
      return List.of();
    }
  }

  private List<ModelIssue> collectModelIssues(
      List<ModelCallRow> modelCalls, List<PayloadRow> payloads) {
    Set<String> truncated =
        payloads.stream()
            .filter(PayloadRow::truncated)
            .map(PayloadRow::modelCallId)
            .collect(java.util.stream.Collectors.toSet());
    List<ModelIssue> result = new ArrayList<>();
    for (ModelCallRow call : modelCalls) {
      if ("FAILED".equals(call.status())) {
        result.add(new ModelIssue(call, classifyModelError(call.errorMessage()), true, false));
        continue;
      }
      long duration = call.durationMs() == null ? 0 : call.durationMs();
      boolean slow = duration >= SLOW_MODEL_THRESHOLD_MS;
      boolean truncatedPayload = truncated.contains(call.id());
      if (slow || truncatedPayload) {
        result.add(new ModelIssue(call, slow ? "超慢调用" : "I/O 快照截断", false, slow));
      }
    }
    return result;
  }

  private static String classifyModelError(String message) {
    if (message == null || message.isBlank()) return "调用失败";
    String text = message.toLowerCase(Locale.ROOT);
    if (text.contains("429")
        || text.contains("rate limit")
        || text.contains("rate_limit")
        || text.contains("限流")) return "429 限流";
    if (text.contains("401")
        || text.contains("unauthorized")
        || text.contains("鉴权")
        || text.contains("api key")) return "401 鉴权失败";
    if (text.contains("timeout") || text.contains("超时")) return "调用超时";
    if (text.contains("context") && (text.contains("length") || text.contains("exceed"))
        || text.contains("token") && text.contains("超")) return "上下文或 Token 超限";
    return "调用失败";
  }

  private Set<String> grantedTools(List<AssemblyRow> assemblies) {
    Set<String> result = new HashSet<>();
    for (AssemblyRow row : assemblies) {
      try {
        JsonNode tools = json.readTree(row.payload()).path("components").path("tools");
        tools.fieldNames().forEachRemaining(result::add);
      } catch (Exception ignored) {
        // 装配快照不可读时按缺少权限处理，提醒用户查看装配配方。
      }
    }
    return result;
  }

  private static NodeRow nodeOf(ToolCallRow call, Map<String, NodeRow> nodesByAgent) {
    return call == null || call.agentId() == null ? null : nodesByAgent.get(call.agentId());
  }

  private static NodeRow nodeOf(ModelCallRow call, Map<String, NodeRow> nodesByAgent) {
    return call == null || call.agentId() == null ? null : nodesByAgent.get(call.agentId());
  }

  private static String labelOf(NodeRow node) {
    if (node == null) return "未知节点";
    if (node.label() != null && !node.label().isBlank()) return node.label();
    if (node.role() != null && !node.role().isBlank()) return node.role();
    return "未命名节点";
  }

  private static String joinLabels(List<NodeRow> nodes) {
    return nodes.stream()
        .map(RunDiagnosticsService::labelOf)
        .distinct()
        .reduce((left, right) -> left + "、" + right)
        .orElse("未知节点");
  }

  private static String runRoute(String runId) {
    return "/runs/" + runId;
  }

  private static String nodeRoute(String runId, String nodeId, String glassbox) {
    return "/runs/" + runId + "?nodeId=" + nodeId + "&glassbox=" + glassbox;
  }

  private static String reasonHint(List<EventRow> events, RunRow run) {
    for (EventRow event : events) {
      if (event.reasonCode() != null && !event.reasonCode().isBlank()) {
        return event.reasonCode() + (event.message() == null ? "" : "（" + event.message() + "）");
      }
    }
    if (run.stopCause() != null && !run.stopCause().isBlank()) return run.stopCause();
    return run.reason();
  }

  private record DenyFact(ToolCallRow call, String gate, String gateLabel, String detail) {}

  private record ModelIssue(ModelCallRow call, String kind, boolean failure, boolean slow) {}

  private record GatewayCheckView(String gate, String label, String status, String detail) {}
}
