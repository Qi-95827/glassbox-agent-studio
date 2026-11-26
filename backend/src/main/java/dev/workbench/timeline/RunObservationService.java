package dev.workbench.timeline;

import dev.workbench.domain.RunSnapshot;
import dev.workbench.framework.workflow.plan.PlanNode;
import dev.workbench.framework.workflow.plan.PlanNodeSpec;
import dev.workbench.persistence.repository.ModelCallPayloadRepository;
import dev.workbench.persistence.repository.RunCheckpointRepository;
import dev.workbench.persistence.repository.ToolApprovalRequestRepository;
import dev.workbench.planning.PlanService;
import dev.workbench.store.AgentAssemblyRecordService;
import dev.workbench.store.RunRecordService;
import dev.workbench.store.TaskRecordService;
import dev.workbench.task.WorkflowExecutionService;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 运行历史的只读投影：时间轴 Span、检查点冻结状态与两次运行的结构化对照。
 *
 * <p>所有数字都来自已落库的记录（计划节点、尝试、模型调用、工具调用、审批单、检查点），不做任何推断式补齐： 某条记录缺失起止时间时该字段就是 {@code null}，并在 {@code limitations} 里说明缺什么，而不是拿相邻时间冒充。
 */
@Service
public class RunObservationService {
  static final int SCHEMA_VERSION = 1;

  private final RunRecordService records;
  private final TaskRecordService tasks;
  private final PlanService plans;
  private final AgentAssemblyRecordService agents;
  private final WorkflowExecutionService workflows;
  private final RunCheckpointRepository checkpoints;
  private final ToolApprovalRequestRepository approvals;
  private final ModelCallPayloadRepository payloads;

  public RunObservationService(
      RunRecordService records,
      TaskRecordService tasks,
      PlanService plans,
      AgentAssemblyRecordService agents,
      WorkflowExecutionService workflows,
      RunCheckpointRepository checkpoints,
      ToolApprovalRequestRepository approvals,
      ModelCallPayloadRepository payloads) {
    this.records = records;
    this.tasks = tasks;
    this.plans = plans;
    this.agents = agents;
    this.workflows = workflows;
    this.checkpoints = checkpoints;
    this.approvals = approvals;
    this.payloads = payloads;
  }

  /** 一层 Span 既可点击定位节点玻璃盒，也带来源记录标识，避免把合并后的图当成原始证据。 */
  public record Span(
      String id,
      String kind,
      String nodeId,
      String nodeKey,
      String label,
      String status,
      String startedAt,
      String finishedAt,
      Long startMs,
      Long endMs,
      Map<String, Object> metrics) {
    public Span {
      metrics = metrics == null ? Map.of() : Map.copyOf(metrics);
    }
  }

  public record RunSummary(
      String status,
      long elapsedMs,
      int rounds,
      int tools,
      int modelCalls,
      int toolCalls,
      long inputTokens,
      long outputTokens,
      int completedNodes,
      int totalNodes) {}

  public record Timeline(
      int schemaVersion,
      String runId,
      RunSummary summary,
      List<Span> spans,
      List<String> limitations) {}

  public record Frozen(
      int completedNodes,
      int totalNodes,
      List<String> completedNodeKeys,
      int handoffs,
      int modelCalls,
      long inputTokens,
      long outputTokens) {}

  public record Checkpoint(
      String id,
      String stage,
      String createdAt,
      Integer version,
      int rounds,
      int tools,
      long elapsedMs,
      String engine,
      String checksum,
      Long eventId,
      Frozen frozen) {}

  public record CheckpointList(int schemaVersion, String runId, List<Checkpoint> checkpoints) {}

  public record Metrics(
      String status,
      long elapsedMs,
      int rounds,
      int tools,
      int modelCalls,
      int toolCalls,
      long inputTokens,
      long outputTokens,
      int completedNodes,
      int totalNodes) {}

  public record NodeSide(
      String status, Long elapsedMs, String output, int modelCalls, int toolCalls, List<String> files) {}

  public record NodeDiff(String key, String title, String role, NodeSide base, NodeSide target) {}

  public record RunDiff(
      int schemaVersion,
      String baseRunId,
      String targetRunId,
      Metrics base,
      Metrics target,
      List<NodeDiff> nodes,
      List<String> onlyInBase,
      List<String> onlyInTarget,
      List<String> limitations) {}

  public Timeline timeline(String runId) {
    requireRun(runId);
    var snapshot = records.runSnapshot(runId);
    var nodes = plans.find(runId);
    var specs = plans.specsByNode(runId);
    var attempts = workflows.workflowAttempts(runId);
    var models = agents.models(runId);
    var tools = agents.tools(runId);
    var limitations = new ArrayList<String>();
    var spans = new ArrayList<Span>();

    var nodeByAgent = new LinkedHashMap<String, PlanNode>();
    for (var node : nodes) {
      if (node.agentId() != null) nodeByAgent.put(node.agentId().toString(), node);
      spans.add(nodeSpan(node, specOf(specs, node)));
    }
    for (var attempt : attempts) {
      var node = attempt.nodeId() == null ? null : findNode(nodes, attempt.nodeId());
      spans.add(
          span(
              "attempt:" + attempt.id(),
              "ATTEMPT",
              attempt.nodeId() == null ? null : attempt.nodeId().toString(),
              node == null ? null : node.key(),
              "尝试 #" + attempt.number(),
              attempt.status() == null ? null : attempt.status().name(),
              attempt.startedAt() == null ? null : attempt.startedAt().toString(),
              attempt.updatedAt() == null ? null : attempt.updatedAt().toString(),              Map.of("agentId", String.valueOf(attempt.agentId()))));
    }
    for (var row : models) {
      var agentId = text(row.get("AGENT_ID"));
      var node = agentId == null ? null : nodeByAgent.get(agentId);
      Long duration = longValue(row.get("DURATION_MS"));
      String created = text(row.get("CREATED_AT"));
      spans.add(
          span(
              "model:" + text(row.get("ID")),
              "MODEL",
              node == null ? null : node.id().toString(),
              node == null ? null : node.key(),
              text(row.get("MODEL_NAME")),
              text(row.get("STATUS")),
              created,
              endOf(created, duration),
              metrics(
                  "inputTokens",
                  longValue(row.get("INPUT_TOKENS")),
                  "outputTokens",
                  longValue(row.get("OUTPUT_TOKENS")),
                  "durationMs",
                  duration)));
    }
    for (var row : tools) {
      var agentId = text(row.get("AGENT_ID"));
      var node = agentId == null ? null : nodeByAgent.get(agentId);
      Long duration = longValue(row.get("DURATION_MS"));
      String created = text(row.get("CREATED_AT"));
      spans.add(
          span(
              "tool:" + text(row.get("ID")),
              "TOOL",
              node == null ? null : node.id().toString(),
              node == null ? null : node.key(),
              text(row.get("TOOL_NAME")),
              text(row.get("STATUS")),
              created,
              endOf(created, duration),
              metrics("durationMs", duration, "toolVersion", longValue(row.get("TOOL_VERSION")))));
    }
    // 6 号门的审批等待本身就是真实耗时：从挂起到决定之间的空档按记录原样呈现。
    for (var approval : approvals.findByRunIdOrderByRequestedAtDesc(runId)) {
      var node = approval.getNodeId() == null ? null : findNode(nodes, approval.getNodeId());
      spans.add(
          span(
              "approval:" + approval.getId(),
              "APPROVAL",
              approval.getNodeId(),
              node == null ? null : node.key(),
              approval.getToolName(),
              approval.getStatus() == null ? null : approval.getStatus().name(),
              approval.getRequestedAt(),
              approval.getDecidedAt(),
              metrics("riskLevel", approval.getRiskLevel())));
    }
    for (var payload : payloads.findByRunId(runId)) {
      if (payload.getCompactionTier() == null || payload.getCompactionTier().isBlank()) continue;
      // 压缩只有「发生时刻」而没有单独耗时，因此是零宽标记，而不是编造一段区间。
      spans.add(
          span(
              "compaction:" + payload.getModelCallId(),
              "COMPACTION",
              null,
              null,
              payload.getCompactionTier(),
              null,
              payload.getCreatedAt(),
              null,
              Map.of()));
    }
    for (var checkpoint : checkpoints.findByRunId(runId)) {
      spans.add(
          span(
              "checkpoint:" + checkpoint.id(),
              "CHECKPOINT",
              null,
              null,
              checkpoint.stage(),
              null,
              checkpoint.createdAt(),
              null,
              metrics("rounds", longValue(checkpoint.rounds()), "tools", longValue(checkpoint.tools()))));
    }
    if (spans.stream().anyMatch(span -> "MODEL".equals(span.kind()) && span.endMs() == null))
      limitations.add("部分模型调用没有记录耗时，时间轴只显示其开始时刻。");
    if (models.isEmpty()) limitations.add("本运行没有可用的模型调用记录，时间轴只有节点与工具。");

    var summary =
        new RunSummary(
            snapshot.status().name(),
            snapshot.elapsedMs(),
            snapshot.rounds(),
            snapshot.tools(),
            models.size(),
            tools.size(),
            sumLong(models, "INPUT_TOKENS"),
            sumLong(models, "OUTPUT_TOKENS"),
            (int) nodes.stream().filter(node -> node.finishedAt() != null).count(),
            nodes.size());
    spans.sort(RunObservationService::byStart);
    return new Timeline(SCHEMA_VERSION, runId, summary, List.copyOf(spans), List.copyOf(limitations));
  }

  public CheckpointList checkpoints(String runId) {
    requireRun(runId);
    var nodes = plans.find(runId);
    var models = agents.models(runId);
    var handoffs = workflows.workflowHandoffs(runId);
    var result = new ArrayList<Checkpoint>();
    for (var row : checkpoints.findByRunId(runId)) {
      var done =
          nodes.stream()
              .filter(node -> node.finishedAt() != null)
              .filter(node -> !after(node.finishedAt().toString(), row.createdAt()))
              .toList();
      var keys = done.stream().map(PlanNode::key).filter(Objects::nonNull).toList();
      int earlyHandoffs =
          (int)
              handoffs.stream()
                  .filter(handoff -> !after(iso(handoff.acceptedAt()), row.createdAt()))
                  .count();
      var earlyModels =
          models.stream()
              .filter(entry -> !after(text(entry.get("CREATED_AT")), row.createdAt()))
              .toList();
      result.add(
          new Checkpoint(
              row.id(),
              row.stage(),
              row.createdAt(),
              row.version(),
              row.rounds() == null ? 0 : row.rounds(),
              row.tools() == null ? 0 : row.tools(),
              row.elapsedMs() == null ? 0 : row.elapsedMs(),
              row.engine(),
              row.checksum(),
              row.eventId(),
              new Frozen(
                  done.size(),
                  nodes.size(),
                  List.copyOf(keys),
                  earlyHandoffs,
                  earlyModels.size(),
                  sumLong(earlyModels, "INPUT_TOKENS"),
                  sumLong(earlyModels, "OUTPUT_TOKENS"))));
    }
    return new CheckpointList(SCHEMA_VERSION, runId, List.copyOf(result));
  }

  /**
   * 两次运行的对照：指标、逐节点产出与声明写入范围。
   *
   * <p>历史运行的交付文件内容不会随运行留存，因此这里只对照「声明写入范围」与节点产出，不伪造代码 diff。
   */
  public RunDiff diff(String baseRunId, String targetRunId) {
    requireRun(baseRunId);
    requireRun(targetRunId);
    var baseNodes = plans.find(baseRunId);
    var targetNodes = plans.find(targetRunId);
    var baseSpecs = plans.specsByNode(baseRunId);
    var targetSpecs = plans.specsByNode(targetRunId);
    var baseAgents = agents.agents(baseRunId);
    var targetAgents = agents.agents(targetRunId);
    var baseModels = agents.models(baseRunId);
    var targetModels = agents.models(targetRunId);
    var baseTools = agents.tools(baseRunId);
    var targetTools = agents.tools(targetRunId);

    var keys = new LinkedHashSet<String>();
    baseNodes.forEach(node -> keys.add(node.key()));
    targetNodes.forEach(node -> keys.add(node.key()));
    var nodes = new ArrayList<NodeDiff>();
    for (String key : keys) {
      var base = findByKey(baseNodes, key);
      var target = findByKey(targetNodes, key);
      nodes.add(
          new NodeDiff(
              key,
              base != null ? base.label() : target != null ? target.label() : null,
              base != null && base.role() != null
                  ? base.role().name()
                  : target != null && target.role() != null ? target.role().name() : null,
              base == null
                  ? null
                  : side(base, baseSpecs.get(base.id()), baseAgents, baseModels, baseTools),
              target == null
                  ? null
                  : side(target, targetSpecs.get(target.id()), targetAgents, targetModels, targetTools)));
    }
    var baseFiles = declaredFiles(baseNodes, baseSpecs);
    var targetFiles = declaredFiles(targetNodes, targetSpecs);
    var onlyInBase = new TreeSet<>(baseFiles);
    onlyInBase.removeAll(targetFiles);
    var onlyInTarget = new TreeSet<>(targetFiles);
    onlyInTarget.removeAll(baseFiles);
    var limitations = new ArrayList<String>();
    limitations.add("历史上没有保存交付文件的逐版本内容，因此这里对照的是声明写入范围与节点产出，而不是代码逐行 diff。");
    return new RunDiff(
        SCHEMA_VERSION,
        baseRunId,
        targetRunId,
        metricsOf(records.runSnapshot(baseRunId), baseNodes, baseModels, baseTools),
        metricsOf(records.runSnapshot(targetRunId), targetNodes, targetModels, targetTools),
        List.copyOf(nodes),
        List.copyOf(onlyInBase),
        List.copyOf(onlyInTarget),
        List.copyOf(limitations));
  }

  private Metrics metricsOf(
      RunSnapshot snapshot, List<PlanNode> nodes, List<Map<String, Object>> models,
      List<Map<String, Object>> tools) {
    return new Metrics(
        snapshot.status().name(),
        snapshot.elapsedMs(),
        snapshot.rounds(),
        snapshot.tools(),
        models.size(),
        tools.size(),
        sumLong(models, "INPUT_TOKENS"),
        sumLong(models, "OUTPUT_TOKENS"),
        (int) nodes.stream().filter(node -> node.finishedAt() != null).count(),
        nodes.size());
  }

  private NodeSide side(
      PlanNode node,
      PlanNodeSpec spec,
      List<Map<String, Object>> agentRows,
      List<Map<String, Object>> models,
      List<Map<String, Object>> tools) {
    String agentId = node.agentId() == null ? null : node.agentId().toString();
    String output =
        agentId == null
            ? null
            : agentRows.stream()
                .filter(row -> agentId.equals(text(row.get("ID"))))
                .map(row -> text(row.get("RESULT")))
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    return new NodeSide(
        node.status() == null ? null : node.status().name(),
        elapsed(node),
        output,
        (int) models.stream().filter(row -> agentId != null && agentId.equals(text(row.get("AGENT_ID")))).count(),
        (int) tools.stream().filter(row -> agentId != null && agentId.equals(text(row.get("AGENT_ID")))).count(),
        spec == null ? List.of() : List.copyOf(spec.writeFiles()));
  }

  private Span nodeSpan(PlanNode node, PlanNodeSpec spec) {
    var metrics = new LinkedHashMap<String, Object>();
    metrics.put("sequence", node.sequence());
    if (spec != null) {
      metrics.put("readFiles", spec.readFiles().size());
      metrics.put("writeFiles", spec.writeFiles().size());
    }
    return span(
        "node:" + node.id(),
        "NODE",
        node.id().toString(),
        node.key(),
        node.label(),
        node.status() == null ? null : node.status().name(),
        node.startedAt() == null ? null : node.startedAt().toString(),
        node.finishedAt() == null ? null : node.finishedAt().toString(),
        metrics);
  }

  private static Span span(
      String id,
      String kind,
      String nodeId,
      String nodeKey,
      String label,
      String status,
      String startedAt,
      String finishedAt,
      Map<String, Object> metrics) {
    return new Span(
        id,
        kind,
        nodeId,
        nodeKey,
        label,
        status,
        startedAt,
        finishedAt,
        epochMillis(startedAt),
        epochMillis(finishedAt),
        metrics);
  }

  private static Map<String, Object> metrics(Object... pairs) {
    var value = new LinkedHashMap<String, Object>();
    for (int index = 0; index + 1 < pairs.length; index += 2) {
      Object item = pairs[index + 1];
      if (item != null) value.put(String.valueOf(pairs[index]), item);
    }
    return value;
  }

  private static PlanNodeSpec specOf(Map<UUID, PlanNodeSpec> specs, PlanNode node) {
    return specs.get(node.id());
  }

  private static PlanNode findNode(List<PlanNode> nodes, UUID id) {
    return nodes.stream().filter(node -> node.id().equals(id)).findFirst().orElse(null);
  }

  private static PlanNode findNode(List<PlanNode> nodes, String id) {
    try {
      return findNode(nodes, UUID.fromString(id));
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private static PlanNode findByKey(List<PlanNode> nodes, String key) {
    return nodes.stream().filter(node -> Objects.equals(node.key(), key)).findFirst().orElse(null);
  }

  private static String iso(Instant value) {
    return value == null ? null : value.toString();
  }

  private static TreeSet<String> declaredFiles(List<PlanNode> nodes, Map<UUID, PlanNodeSpec> specs) {
    var files = new TreeSet<String>();
    for (var node : nodes) {
      var spec = specs.get(node.id());
      if (spec != null) files.addAll(spec.writeFiles());
    }
    return files;
  }

  private static Long elapsed(PlanNode node) {
    if (node.startedAt() == null || node.finishedAt() == null) return null;
    return node.finishedAt().toEpochMilli() - node.startedAt().toEpochMilli();
  }

  private static int byStart(Span left, Span right) {
    Long a = left.startMs();
    Long b = right.startMs();
    if (a == null && b == null) return left.id().compareTo(right.id());
    if (a == null) return 1;
    if (b == null) return -1;
    int order = Long.compare(a, b);
    return order != 0 ? order : left.id().compareTo(right.id());
  }

  private static String endOf(String start, Long durationMs) {
    Long startMs = epochMillis(start);
    if (startMs == null || durationMs == null) return null;
    return Instant.ofEpochMilli(startMs + durationMs).toString();
  }

  private static Long epochMillis(String value) {    if (value == null || value.isBlank()) return null;
    try {
      return Instant.parse(value).toEpochMilli();
    } catch (DateTimeParseException e) {
      return null;
    }
  }

  /** 时间戳缺失或格式不可解析时按「不早于」处理，避免把无法比较的记录算进冻结状态。 */
  private static boolean after(String value, String boundary) {
    Long left = epochMillis(value);
    Long right = epochMillis(boundary);
    if (left == null || right == null) return true;
    return left > right;
  }

  private static long sumLong(List<Map<String, Object>> rows, String key) {
    long total = 0;
    for (var row : rows) {
      Long value = longValue(row.get(key));
      if (value != null) total += value;
    }
    return total;
  }

  private static String text(Object value) {
    if (value == null) return null;
    String text = String.valueOf(value);
    return text.isBlank() ? null : text;
  }

  private static Long longValue(Object value) {
    if (value == null) return null;
    if (value instanceof Number number) return number.longValue();
    try {
      return Long.parseLong(String.valueOf(value));
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private void requireRun(String runId) {
    records.runSnapshot(runId);
  }
}
