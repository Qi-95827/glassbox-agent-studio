package dev.workbench.observability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.workbench.domain.RunStatus;
import dev.workbench.domain.TaskStatus;
import dev.workbench.ownership.OwnershipService;
import dev.workbench.persistence.repository.MonitoringDashboardRepository;
import dev.workbench.persistence.repository.MonitoringDashboardRepository.CostTotals;
import dev.workbench.persistence.repository.MonitoringDashboardRepository.GatewayTraceRow;
import dev.workbench.persistence.repository.MonitoringDashboardRepository.ModelCallRow;
import dev.workbench.persistence.repository.MonitoringDashboardRepository.NodeRow;
import dev.workbench.persistence.repository.MonitoringDashboardRepository.RunRow;
import dev.workbench.persistence.repository.MonitoringDashboardRepository.ToolCallRow;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 聚合细粒度运行监控看板；所有统计只来自当前用户可读任务的持久化证据。 */
@Service
public class MonitoringDashboardService {
  private static final Set<String> ANOMALY_RUN_STATUSES =
      Set.of("FAILED", "BLOCKED", "LIMITED", "INTERRUPTED");
  private static final Set<String> TERMINAL_RUN_STATUSES =
      Set.of("COMPLETED", "FAILED", "BLOCKED", "LIMITED", "INTERRUPTED", "CANCELLED");
  private static final Set<String> ANOMALY_NODE_STATUSES =
      Set.of("FAILED", "LIMITED", "INTERRUPTED");

  private final MonitoringDashboardRepository repository;
  private final OwnershipService ownership;
  private final ObjectMapper json;

  /** 任务筛选下拉的硬上限：只用于筛选，不承担全量任务列表。 */
  private static final int MAX_TASK_OPTIONS = 200;

  public MonitoringDashboardService(
      MonitoringDashboardRepository repository, OwnershipService ownership, ObjectMapper json) {
    this.repository = repository;
    this.ownership = ownership;
    this.json = json;
  }

  /** 兼容原全局看板：不传任务/运行时行为与历史完全一致。 */
  @Transactional(readOnly = true)
  public MonitoringDashboard dashboard(String range, String status, String model) {
    return dashboard(range, status, model, null, null);
  }

  /**
   * 全局或单任务监控看板。
   *
   * <p>任务的收敛口径只有一份：{@code taskId} 传入时先核对它确实位于当前账号可读任务集合中，越权或不存在直接 404，
   * 绝不回退成全局数据；{@code runId} 再在同一范围内精确过滤一条运行。KPI、趋势、分布与排行全部基于过滤后的同一份
   * runs 集合计算，不存在口径漂移。
   */
  @Transactional(readOnly = true)
  public MonitoringDashboard dashboard(
      String range, String status, String model, String taskId, String runId) {
    String normalized = normalizeRange(range);
    String statusFilter = blankToNull(status);
    String modelFilter = blankToNull(model);
    String scopedTaskId = blankToNull(taskId);
    String scopedRunId = blankToNull(runId);
    Instant now = Instant.now();
    Instant from = rangeStart(normalized, now);
    var readable = ownership.readableTaskIds();
    var taskOptions = taskOptions(readable);
    if (scopedTaskId != null && !readable.contains(scopedTaskId))
      throw new org.springframework.dao.EmptyResultDataAccessException(1);
    String scopedGoal = null;
    for (var option : taskOptions)
      if (option.taskId().equals(scopedTaskId)) scopedGoal = option.goal();
    if (scopedRunId != null) {
      // 运行归属先校验：跨账号运行一律 404，且运行必须落在被筛选的任务内。
      ownership.requireRun(scopedRunId);
      String runTaskId = ownership.taskIdOfRun(scopedRunId);
      if (scopedTaskId != null && !scopedTaskId.equals(runTaskId))
        throw new org.springframework.dao.EmptyResultDataAccessException(1);
      scopedTaskId = runTaskId;
    }
    if (scopedGoal == null && scopedTaskId != null)
      for (var option : taskOptions)
        if (option.taskId().equals(scopedTaskId)) scopedGoal = option.goal();

    List<String> taskIds =
        scopedTaskId == null ? readable : List.of(scopedTaskId);
    if (taskIds.isEmpty())
      return empty(normalized, scopedTaskId, scopedGoal, taskOptions);

    List<RunRow> runs = repository.runs(taskIds, from == null ? null : from.toString());
    if (scopedRunId != null) {
      String exact = scopedRunId;
      runs = runs.stream().filter(run -> exact.equals(run.id())).toList();
    }
    if (statusFilter != null) {
      String wanted = statusFilter.toUpperCase(Locale.ROOT);
      runs = runs.stream().filter(run -> wanted.equals(run.status())).toList();
    }
    List<String> runIds = runs.stream().map(RunRow::id).toList();
    List<ModelCallRow> modelCalls = repository.modelCalls(runIds);
    if (modelFilter != null) {
      Set<String> matchingRuns =
          modelCalls.stream()
              .filter(call -> modelFilter.equalsIgnoreCase(call.modelName()))
              .map(ModelCallRow::runId)
              .collect(java.util.stream.Collectors.toSet());
      runs = runs.stream().filter(run -> matchingRuns.contains(run.id())).toList();
      Set<String> filteredRunIds =
          runs.stream().map(RunRow::id).collect(java.util.stream.Collectors.toSet());
      runIds = runs.stream().map(RunRow::id).toList();
      modelCalls =
          modelCalls.stream().filter(call -> filteredRunIds.contains(call.runId())).toList();
    }
    if (runs.isEmpty()) return empty(normalized, scopedTaskId, scopedGoal, taskOptions);

    List<ToolCallRow> toolCalls = repository.toolCalls(runIds);
    List<NodeRow> nodes = repository.nodes(runIds);
    List<GatewayTraceRow> traces = repository.gatewayTraces(runIds);
    CostTotals costTotals = repository.costTotals(runIds);
    Set<String> deliveredWorkspaces = repository.deliveredWorkspaceIds();
    GatewayScan gatewayScan = scanGateway(traces);

    Map<String, List<NodeRow>> nodesByRun =
        nodes.stream().collect(java.util.stream.Collectors.groupingBy(NodeRow::runId));
    Map<String, RunAggregate> aggregates = aggregate(modelCalls, toolCalls, nodes, gatewayScan);
    Map<String, String> taskStatuses = new LinkedHashMap<>();
    for (RunRow run : runs) {
      taskStatuses.put(run.id(), taskStatus(run, deliveredWorkspaces));
    }

    MonitoringDashboard.Summary summary =
        summary(runs, aggregates, taskStatuses, gatewayScan, costTotals);
    return new MonitoringDashboard(
        normalized,
        now,
        summary,
        timeSeries(normalized, runs, aggregates, now),
        distributions(runs, taskStatuses, modelCalls, toolCalls, nodes, gatewayScan),
        rankings(runs, aggregates, nodesByRun, taskStatuses),
        scopedTaskId,
        scopedGoal,
        taskOptions);
  }

  /** 当前账号可读任务的筛选选项；按最近活跃排序并限制条数。 */
  private List<MonitoringDashboard.TaskOption> taskOptions(List<String> readable) {
    if (readable.isEmpty()) return List.of();
    return repository.taskOptions(readable, MAX_TASK_OPTIONS).stream()
        .map(
            row ->
                new MonitoringDashboard.TaskOption(
                    row.taskId(), row.goal(), row.latestStatus(), row.runCount()))
        .toList();
  }

  private MonitoringDashboard.Summary summary(
      List<RunRow> runs,
      Map<String, RunAggregate> aggregates,
      Map<String, String> taskStatuses,
      GatewayScan gatewayScan,
      CostTotals costTotals) {
    long completed = runs.stream().filter(run -> "COMPLETED".equals(run.status())).count();
    long delivered =
        taskStatuses.values().stream().filter(TaskStatus.DELIVERED.name()::equals).count();
    long failed = runs.stream().filter(run -> "FAILED".equals(run.status())).count();
    long blocked = runs.stream().filter(run -> "BLOCKED".equals(run.status())).count();
    long anomalies =
        runs.stream()
            .filter(run -> isAnomaly(run, aggregates.get(run.id())))
            .count();
    long modelCalls = aggregates.values().stream().mapToLong(value -> value.modelCalls).sum();
    long inputTokens = aggregates.values().stream().mapToLong(value -> value.inputTokens).sum();
    long outputTokens = aggregates.values().stream().mapToLong(value -> value.outputTokens).sum();
    long toolCalls = aggregates.values().stream().mapToLong(value -> value.toolCalls).sum();
    long toolDenies = aggregates.values().stream().mapToLong(value -> value.toolDenies).sum();

    List<Long> durations =
        runs.stream()
            .filter(run -> TERMINAL_RUN_STATUSES.contains(run.status()))
            .map(RunRow::elapsedMs)
            .filter(value -> value > 0)
            .sorted()
            .toList();
    return new MonitoringDashboard.Summary(
        runs.size(),
        completed,
        delivered,
        failed,
        blocked,
        anomalies,
        ratio(completed, runs.size()),
        average(durations),
        percentile(durations, 0.95),
        modelCalls,
        inputTokens,
        outputTokens,
        costTotals.estimatedAmount(),
        toolCalls,
        toolDenies,
        ratio(toolDenies, toolCalls));
  }

  private List<MonitoringDashboard.TimeSeriesPoint> timeSeries(
      String range,
      List<RunRow> runs,
      Map<String, RunAggregate> aggregates,
      Instant now) {
    if (runs.isEmpty()) return List.of();
    ZoneId zone = ZoneId.systemDefault();
    List<Bucket> buckets = buckets(range, runs, now, zone);
    for (Bucket bucket : buckets) {
      for (RunRow run : runs) {
        Instant createdAt = parseInstant(run.createdAt());
        if (createdAt == null) continue;
        Instant start = truncate(createdAt, range, zone);
        if (!start.equals(bucket.start)) continue;
        RunAggregate aggregate = aggregates.get(run.id());
        bucket.runCount++;
        if ("COMPLETED".equals(run.status())) bucket.successCount++;
        if ("FAILED".equals(run.status())) bucket.failedCount++;
        if (TERMINAL_RUN_STATUSES.contains(run.status()) && run.elapsedMs() > 0) {
          bucket.durationSum += run.elapsedMs();
          bucket.durationCount++;
        }
        if (aggregate != null) bucket.totalTokens += aggregate.totalTokens();
      }
    }
    return buckets.stream()
        .map(
            bucket ->
                new MonitoringDashboard.TimeSeriesPoint(
                    bucket.label,
                    bucket.start.toString(),
                    bucket.runCount,
                    bucket.successCount,
                    bucket.failedCount,
                    bucket.durationCount == 0 ? null : bucket.durationSum / bucket.durationCount,
                    bucket.totalTokens))
        .toList();
  }

  private List<Bucket> buckets(String range, List<RunRow> runs, Instant now, ZoneId zone) {
    List<Bucket> result = new ArrayList<>();
    if ("24h".equals(range)) {
      Instant start = ZonedDateTime.ofInstant(now, zone).truncatedTo(ChronoUnit.HOURS).toInstant();
      for (int index = 23; index >= 0; index--) {
        result.add(bucket(start.minus(index, ChronoUnit.HOURS), "HH:00", zone));
      }
      return result;
    }
    int days = "7d".equals(range) ? 7 : 30;
    if ("all".equals(range)) {
      Instant earliest =
          runs.stream()
              .map(run -> parseInstant(run.createdAt()))
              .filter(Objects::nonNull)
              .min(Comparator.naturalOrder())
              .orElse(now);
      Instant start = truncate(earliest, range, zone);
      Instant end = truncate(now, range, zone);
      long span = ChronoUnit.DAYS.between(start, end) + 1;
      if (span > 366) start = end.minus(365, ChronoUnit.DAYS);
      for (Instant cursor = start; !cursor.isAfter(end); cursor = cursor.plus(1, ChronoUnit.DAYS)) {
        result.add(bucket(cursor, "MM-dd", zone));
      }
      return result;
    }
    Instant end = truncate(now, range, zone);
    for (int index = days - 1; index >= 0; index--) {
      result.add(bucket(end.minus(index, ChronoUnit.DAYS), "MM-dd", zone));
    }
    return result;
  }

  private static Bucket bucket(Instant start, String pattern, ZoneId zone) {
    String label = DateTimeFormatter.ofPattern(pattern).format(start.atZone(zone));
    return new Bucket(start, label);
  }

  private MonitoringDashboard.Distributions distributions(
      List<RunRow> runs,
      Map<String, String> taskStatuses,
      List<ModelCallRow> modelCalls,
      List<ToolCallRow> toolCalls,
      List<NodeRow> nodes,
      GatewayScan gatewayScan) {
    Map<String, Long> taskCounts = new LinkedHashMap<>();
    for (String status : orderedTaskStatuses()) taskCounts.put(status, 0L);
    for (String status : taskStatuses.values()) taskCounts.merge(status, 1L, Long::sum);
    List<MonitoringDashboard.TaskStatusSlice> taskSlices =
        taskCounts.entrySet().stream()
            .filter(entry -> entry.getValue() > 0)
            .map(
                entry ->
                    new MonitoringDashboard.TaskStatusSlice(
                        entry.getKey(), entry.getValue(), ratio(entry.getValue(), runs.size())))
            .toList();

    Map<String, ModelAccumulator> modelGroups = new LinkedHashMap<>();
    for (ModelCallRow call : modelCalls) {
      ModelAccumulator group =
          modelGroups.computeIfAbsent(call.modelName(), key -> new ModelAccumulator());
      group.calls++;
      if (call.inputTokens() != null) group.inputTokens += call.inputTokens();
      if (call.outputTokens() != null) group.outputTokens += call.outputTokens();
      if (call.durationMs() != null) {
        group.durationSum += call.durationMs();
        group.durationCount++;
      }
      if ("FAILED".equals(call.status())) group.failedCalls++;
    }
    List<MonitoringDashboard.ModelSlice> modelSlices =
        modelGroups.entrySet().stream()
            .sorted(
                Map.Entry.comparingByValue(
                    Comparator.comparingLong((ModelAccumulator value) -> -value.calls)))
            .map(
                entry ->
                    new MonitoringDashboard.ModelSlice(
                        entry.getKey(),
                        entry.getValue().calls,
                        entry.getValue().inputTokens + entry.getValue().outputTokens,
                        entry.getValue().durationCount == 0
                            ? null
                            : entry.getValue().durationSum / entry.getValue().durationCount,
                        entry.getValue().failedCalls))
            .toList();

    Map<String, ToolAccumulator> toolGroups = new LinkedHashMap<>();
    for (ToolCallRow call : toolCalls) {
      ToolAccumulator group =
          toolGroups.computeIfAbsent(call.toolName(), key -> new ToolAccumulator());
      group.calls++;
      if ("COMPLETED".equals(call.status())) group.passedCalls++;
      else if (!"RUNNING".equals(call.status())) group.failedCalls++;
      if (call.durationMs() != null) {
        group.durationSum += call.durationMs();
        group.durationCount++;
      }
    }
    List<MonitoringDashboard.ToolSlice> toolSlices =
        toolGroups.entrySet().stream()
            .sorted(
                Map.Entry.comparingByValue(
                    Comparator.comparingLong((ToolAccumulator value) -> -value.calls)))
            .map(
                entry ->
                    new MonitoringDashboard.ToolSlice(
                        entry.getKey(),
                        entry.getValue().calls,
                        entry.getValue().passedCalls,
                        entry.getValue().failedCalls,
                        entry.getValue().durationCount == 0
                            ? null
                            : entry.getValue().durationSum / entry.getValue().durationCount))
            .toList();

    Map<String, ToolCallRow> callsById = new LinkedHashMap<>();
    for (ToolCallRow call : toolCalls) callsById.put(call.id(), call);
    Map<String, NodeRow> nodesByAgent = new LinkedHashMap<>();
    for (NodeRow node : nodes) {
      if (node.agentId() != null) nodesByAgent.putIfAbsent(node.agentId(), node);
    }
    List<MonitoringDashboard.GatewayDenySlice> gatewaySlices =
        gatewayScan.gateCounts.entrySet().stream()
            .sorted(Map.Entry.comparingByValue(Comparator.reverseOrder()))
            .map(
                entry -> {
                  GatewaySample sample = gatewayScan.gateSamples.get(entry.getKey());
                  ToolCallRow call = sample == null ? null : callsById.get(sample.toolCallId());
                  NodeRow node =
                      call == null || call.agentId() == null ? null : nodesByAgent.get(call.agentId());
                  return new MonitoringDashboard.GatewayDenySlice(
                      entry.getKey(),
                      gatewayScan.gateLabels.getOrDefault(entry.getKey(), entry.getKey()),
                      entry.getValue(),
                      sample == null ? null : sample.runId(),
                      node == null ? null : node.id());
                })
            .toList();

    return new MonitoringDashboard.Distributions(taskSlices, modelSlices, toolSlices, gatewaySlices);
  }

  private MonitoringDashboard.Rankings rankings(
      List<RunRow> runs,
      Map<String, RunAggregate> aggregates,
      Map<String, List<NodeRow>> nodesByRun,
      Map<String, String> taskStatuses) {
    List<MonitoringDashboard.RecentRun> recent =
        runs.stream()
            .limit(50)
            .map(
                run -> {
                  RunAggregate aggregate = aggregates.getOrDefault(run.id(), new RunAggregate());
                  return new MonitoringDashboard.RecentRun(
                      run.id(),
                      run.taskId(),
                      run.taskGoal(),
                      taskStatuses.get(run.id()),
                      run.status(),
                      run.stopCause(),
                      aggregate.modelLabel(),
                      aggregate.nodeCount,
                      run.elapsedMs() > 0 ? run.elapsedMs() : null,
                      aggregate.inputTokens,
                      aggregate.outputTokens,
                      aggregate.toolCalls,
                      aggregate.toolDenies,
                      isAnomaly(run, aggregate),
                      run.createdAt());
                })
            .toList();

    List<MonitoringDashboard.SlowNode> slowest =
        nodesByRun.values().stream()
            .flatMap(List::stream)
            .filter(node -> !"SYSTEM_CHECK".equals(node.kind()))
            .map(
                node -> {
                  Long duration = nodeDuration(node);
                  return duration == null
                      ? null
                      : new MonitoringDashboard.SlowNode(
                          node.runId(),
                          node.id(),
                          node.label(),
                          node.role(),
                          modelOfNode(node, aggregates),
                          duration);
                })
            .filter(Objects::nonNull)
            .sorted(
                Comparator.comparing(
                        MonitoringDashboard.SlowNode::durationMs,
                        Comparator.nullsLast(Comparator.reverseOrder())))
            .limit(10)
            .toList();

    List<MonitoringDashboard.HighTokenRun> highTokens =
        runs.stream()
            .map(
                run -> {
                  RunAggregate aggregate = aggregates.getOrDefault(run.id(), new RunAggregate());
                  return new MonitoringDashboard.HighTokenRun(
                      run.id(),
                      run.taskGoal(),
                      aggregate.modelLabel(),
                      aggregate.totalTokens(),
                      aggregate.inputTokens,
                      aggregate.outputTokens);
                })
            .filter(run -> run.totalTokens() > 0)
            .sorted(
                Comparator.comparingLong(MonitoringDashboard.HighTokenRun::totalTokens).reversed())
            .limit(10)
            .toList();

    return new MonitoringDashboard.Rankings(recent, slowest, highTokens);
  }

  private Map<String, RunAggregate> aggregate(
      List<ModelCallRow> modelCalls,
      List<ToolCallRow> toolCalls,
      List<NodeRow> nodes,
      GatewayScan gatewayScan) {
    Map<String, RunAggregate> aggregates = new LinkedHashMap<>();
    for (ModelCallRow call : modelCalls) {
      RunAggregate aggregate = aggregates.computeIfAbsent(call.runId(), key -> new RunAggregate());
      aggregate.modelCalls++;
      aggregate.models.add(call.modelName());
      aggregate.modelsByAgent
          .computeIfAbsent(call.agentId(), key -> new java.util.LinkedHashSet<>())
          .add(call.modelName());
      if (call.inputTokens() != null) aggregate.inputTokens += call.inputTokens();
      if (call.outputTokens() != null) aggregate.outputTokens += call.outputTokens();
      if ("FAILED".equals(call.status())) aggregate.modelFailures++;
    }
    for (ToolCallRow call : toolCalls) {
      RunAggregate aggregate = aggregates.computeIfAbsent(call.runId(), key -> new RunAggregate());
      aggregate.toolCalls++;
      if (isDeniedTool(call, gatewayScan)) aggregate.toolDenies++;
    }
    for (NodeRow node : nodes) {
      RunAggregate aggregate = aggregates.computeIfAbsent(node.runId(), key -> new RunAggregate());
      if (!"SYSTEM_CHECK".equals(node.kind())) aggregate.nodeCount++;
      if (ANOMALY_NODE_STATUSES.contains(node.status())) aggregate.failedNodes++;
    }
    return aggregates;
  }

  private GatewayScan scanGateway(List<GatewayTraceRow> traces) {
    Map<String, Long> gateCounts = new LinkedHashMap<>();
    Map<String, String> gateLabels = new LinkedHashMap<>();
    Map<String, GatewaySample> gateSamples = new LinkedHashMap<>();
    Set<String> deniedToolCalls = new HashSet<>();
    for (GatewayTraceRow trace : traces) {
      List<GatewayCheckView> checks = parseChecks(trace.gatewayChecksJson());
      for (GatewayCheckView check : checks) {
        if (!"DENY".equalsIgnoreCase(check.status())) continue;
        deniedToolCalls.add(trace.toolCallId());
        gateCounts.merge(check.gate(), 1L, Long::sum);
        gateSamples.putIfAbsent(
            check.gate(), new GatewaySample(trace.runId(), trace.toolCallId()));
        if (check.label() != null && !check.label().isBlank()) {
          gateLabels.putIfAbsent(check.gate(), check.label());
        }
      }
    }
    return new GatewayScan(deniedToolCalls, gateCounts, gateLabels, gateSamples);
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
      // 历史或手工写入的轨迹无法解析时按无安检记录处理，不能阻断看板。
      return List.of();
    }
  }

  private boolean isDeniedTool(ToolCallRow call, GatewayScan scan) {
    if (scan.deniedToolCalls.contains(call.id())) return true;
    if (!"FAILED".equals(call.status()) || call.errorMessage() == null) return false;
    String message = call.errorMessage();
    return message.contains("工具输入")
        || message.contains("安检")
        || message.contains("白名单")
        || message.contains("授权")
        || message.contains("拒绝");
  }

  private boolean isAnomaly(RunRow run, RunAggregate aggregate) {
    if (ANOMALY_RUN_STATUSES.contains(run.status())) return true;
    if (aggregate == null) return false;
    return aggregate.toolDenies > 0 || aggregate.modelFailures > 0 || aggregate.failedNodes > 0;
  }

  private String modelOfNode(NodeRow node, Map<String, RunAggregate> aggregates) {
    RunAggregate aggregate = aggregates.get(node.runId());
    if (aggregate == null) return null;
    Set<String> models = aggregate.modelsByAgent.get(node.agentId());
    if (models == null || models.isEmpty()) return aggregate.modelLabel();
    if (models.size() == 1) return models.iterator().next();
    return "mixed";
  }

  private static Long nodeDuration(NodeRow node) {
    Instant start = parseInstant(node.startedAt());
    Instant finish = parseInstant(node.finishedAt());
    if (start == null || finish == null || finish.isBefore(start)) return null;
    long value = Duration.between(start, finish).toMillis();
    return value > 0 ? value : null;
  }

  private String taskStatus(RunRow run, Set<String> deliveredWorkspaces) {
    boolean hasWorkspace = run.workspaceId() != null && !run.workspaceId().isBlank();
    boolean delivered = hasWorkspace && deliveredWorkspaces.contains(run.workspaceId());
    return TaskStatus.derive(run.status(), hasWorkspace, delivered).name();
  }

  private static List<String> orderedTaskStatuses() {
    return List.of(
        TaskStatus.COMPLETED.name(),
        TaskStatus.DELIVERED.name(),
        TaskStatus.AWAITING_REVIEW.name(),
        TaskStatus.IN_PROGRESS.name(),
        TaskStatus.BLOCKED.name(),
        TaskStatus.FAILED.name(),
        TaskStatus.CANCELLED.name());
  }

  private static MonitoringDashboard empty(
      String range,
      String selectedTaskId,
      String selectedTaskGoal,
      List<MonitoringDashboard.TaskOption> taskOptions) {
    return new MonitoringDashboard(
        range,
        Instant.now(),
        new MonitoringDashboard.Summary(0, 0, 0, 0, 0, 0, 0, null, null, 0, 0, 0, null, 0, 0, 0),
        List.of(),
        new MonitoringDashboard.Distributions(List.of(), List.of(), List.of(), List.of()),
        new MonitoringDashboard.Rankings(List.of(), List.of(), List.of()),
        selectedTaskId,
        selectedTaskGoal,
        taskOptions);
  }

  private static String normalizeRange(String range) {
    if (range == null) return "7d";
    return switch (range.trim().toLowerCase(Locale.ROOT)) {
      case "24h", "7d", "30d", "all" -> range.trim().toLowerCase(Locale.ROOT);
      default -> "7d";
    };
  }

  private static Instant rangeStart(String range, Instant now) {
    ZoneId zone = ZoneId.systemDefault();
    return switch (range) {
      case "24h" -> ZonedDateTime.ofInstant(now, zone).truncatedTo(ChronoUnit.HOURS)
          .minusHours(23)
          .toInstant();
      case "30d" -> ZonedDateTime.ofInstant(now, zone).truncatedTo(ChronoUnit.DAYS)
          .minusDays(29)
          .toInstant();
      case "7d" -> ZonedDateTime.ofInstant(now, zone).truncatedTo(ChronoUnit.DAYS)
          .minusDays(6)
          .toInstant();
      default -> null;
    };
  }

  private static Instant truncate(Instant instant, String range, ZoneId zone) {
    ZonedDateTime zoned = ZonedDateTime.ofInstant(instant, zone);
    return ("24h".equals(range) ? zoned.truncatedTo(ChronoUnit.HOURS)
            : zoned.truncatedTo(ChronoUnit.DAYS))
        .toInstant();
  }

  private static Instant parseInstant(String value) {
    if (value == null || value.isBlank()) return null;
    try {
      return Instant.parse(value);
    } catch (RuntimeException ignored) {
      return null;
    }
  }

  private static Long average(List<Long> values) {
    if (values.isEmpty()) return null;
    long sum = 0;
    for (long value : values) sum += value;
    return sum / values.size();
  }

  private static Long percentile(List<Long> sorted, double percentile) {
    if (sorted.isEmpty()) return null;
    int index = (int) Math.ceil(percentile * sorted.size()) - 1;
    return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
  }

  private static double ratio(long numerator, long denominator) {
    return denominator <= 0 ? 0.0 : numerator * 100.0 / denominator;
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private static final class Bucket {
    private final Instant start;
    private final String label;
    private long runCount;
    private long successCount;
    private long failedCount;
    private long durationSum;
    private long durationCount;
    private long totalTokens;

    private Bucket(Instant start, String label) {
      this.start = start;
      this.label = label;
    }
  }

  private static final class RunAggregate {
    private long modelCalls;
    private long modelFailures;
    private long inputTokens;
    private long outputTokens;
    private long toolCalls;
    private long toolDenies;
    private int nodeCount;
    private long failedNodes;
    private final Set<String> models = new java.util.LinkedHashSet<>();
    private final Map<String, Set<String>> modelsByAgent = new LinkedHashMap<>();

    private long totalTokens() {
      return inputTokens + outputTokens;
    }

    private String modelLabel() {
      if (models.isEmpty()) return null;
      if (models.size() == 1) return models.iterator().next();
      return "mixed";
    }
  }

  private static final class ModelAccumulator {
    private long calls;
    private long inputTokens;
    private long outputTokens;
    private long durationSum;
    private long durationCount;
    private long failedCalls;
  }

  private static final class ToolAccumulator {
    private long calls;
    private long passedCalls;
    private long failedCalls;
    private long durationSum;
    private long durationCount;
  }

  private record GatewayCheckView(String gate, String label, String status, String detail) {}

  private record GatewaySample(String runId, String toolCallId) {}

  private record GatewayScan(
      Set<String> deniedToolCalls,
      Map<String, Long> gateCounts,
      Map<String, String> gateLabels,
      Map<String, GatewaySample> gateSamples) {}
}
