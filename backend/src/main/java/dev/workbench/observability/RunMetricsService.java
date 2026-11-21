package dev.workbench.observability;

import dev.workbench.domain.InvocationStatus;
import dev.workbench.framework.workflow.check.SystemCheckStatus;
import dev.workbench.framework.workflow.plan.PlanNodeStatus;
import dev.workbench.planning.PlanService;
import dev.workbench.store.AgentAssemblyRecordService;
import dev.workbench.store.RunStateStore;
import dev.workbench.task.WorkflowExecutionService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** 按真实 Agent 实例关联调用；统计是数据库快照，不推算未知用量。 */
@Service
public class RunMetricsService {
  private final RunStateStore runs;
  private final AgentAssemblyRecordService records;
  private final PlanService plans;
  private final WorkflowExecutionService workflows;

  public RunMetricsService(
      RunStateStore runs,
      AgentAssemblyRecordService records,
      PlanService plans,
      WorkflowExecutionService workflows) {
    this.runs = runs;
    this.records = records;
    this.plans = plans;
    this.workflows = workflows;
  }

  /**
   * 计划节点、系统检查与实际调用台账的只读汇总。
   *
   * <p>检查节点不产生模型或工具调用；“未执行检查”指没有检查记录且节点未完成的系统检查。
   */
  public WorkflowMetrics workflow(String runId) {
    runs.runSnapshot(runId);
    var nodes = plans.find(runId);
    var checks = workflows.workflowChecks(runId);
    var models = records.models(runId);
    var tools = records.tools(runId);
    var nodeCounts = new java.util.EnumMap<PlanNodeStatus, Long>(PlanNodeStatus.class);
    for (var status : PlanNodeStatus.values()) nodeCounts.put(status, 0L);
    nodes.forEach(node -> nodeCounts.merge(node.status(), 1L, Long::sum));
    var checkCounts = new java.util.EnumMap<SystemCheckStatus, Long>(SystemCheckStatus.class);
    for (var status : SystemCheckStatus.values()) checkCounts.put(status, 0L);
    checks.forEach(check -> checkCounts.merge(check.status(), 1L, Long::sum));
    long unexecutedChecks =
        nodes.stream()
            .filter(
                node ->
                    node.systemCheck()
                        && (node.status() == PlanNodeStatus.PENDING
                            || node.status() == PlanNodeStatus.SKIPPED))
            .count();
    long unknownTokens =
        models.stream()
            .filter(call -> call.get("INPUT_TOKENS") == null || call.get("OUTPUT_TOKENS") == null)
            .count();
    return new WorkflowMetrics(
        nodes.size(),
        Map.copyOf(nodeCounts),
        checks.size(),
        Map.copyOf(checkCounts),
        unexecutedChecks,
        models.size(),
        tools.size(),
        unknownTokens);
  }

  public List<AgentMetrics> agents(String runId) {
    runs.runSnapshot(runId);
    var models = records.models(runId);
    var tools = records.tools(runId);
    return records.agents(runId).stream()
        .map(
            agent -> {
              String id = agent.get("ID").toString();
              var ownModels = models.stream().filter(m -> id.equals(m.get("AGENT_ID"))).toList();
              var ownTools = tools.stream().filter(t -> id.equals(t.get("AGENT_ID"))).toList();
              Long duration = knownAgentWallDuration(agent);
              return new AgentMetrics(
                  UUID.fromString(id),
                  agent.get("ROLE_NAME").toString(),
                  duration,
                  ownModels.size(),
                  knownSumOrZero(ownModels, "DURATION_MS"),
                  unknownCount(ownModels, "DURATION_MS"),
                  knownSum(ownModels, "INPUT_TOKENS"),
                  unknownCount(ownModels, "INPUT_TOKENS"),
                  knownSum(ownModels, "OUTPUT_TOKENS"),
                  unknownCount(ownModels, "OUTPUT_TOKENS"),
                  ownTools.size(),
                  knownSumOrZero(ownTools, "DURATION_MS"),
                  unknownCount(ownTools, "DURATION_MS"),
                  ownTools.stream().map(t -> t.get("TOOL_NAME").toString()).distinct().toList(),
                  groupTools(ownTools));
            })
        .toList();
  }

  /** 中断可能在重启后才落库，更新时间不能视作实际结束时刻。 */
  private static Long knownAgentWallDuration(Map<String, Object> agent) {
    try {
      var status = InvocationStatus.valueOf(String.valueOf(agent.get("STATUS")));
      if (status == InvocationStatus.RUNNING || status == InvocationStatus.INTERRUPTED) return null;
      long millis =
          Duration.between(
                  Instant.parse(agent.get("CREATED_AT").toString()),
                  Instant.parse(agent.get("UPDATED_AT").toString()))
              .toMillis();
      return millis < 0 ? null : millis;
    } catch (IllegalArgumentException
        | java.time.DateTimeException
        | NullPointerException
        | ArithmeticException e) {
      return null;
    }
  }

  private static List<ToolMetrics> groupTools(List<Map<String, Object>> tools) {
    var groups =
        tools.stream()
            .collect(
                java.util.stream.Collectors.groupingBy(
                    row -> row.get("TOOL_NAME").toString(),
                    java.util.LinkedHashMap::new,
                    java.util.stream.Collectors.toList()));
    return groups.entrySet().stream()
        .map(
            entry -> {
              Map<InvocationStatus, Long> counts = new java.util.EnumMap<>(InvocationStatus.class);
              long unknown = 0;
              for (var row : entry.getValue()) {
                try {
                  var status = InvocationStatus.valueOf(String.valueOf(row.get("STATUS")));
                  counts.merge(status, 1L, Long::sum);
                } catch (IllegalArgumentException e) {
                  unknown++;
                }
              }
              return new ToolMetrics(
                  entry.getKey(),
                  entry.getValue().size(),
                  counts,
                  unknown,
                  knownSum(entry.getValue(), "DURATION_MS"),
                  unknownCount(entry.getValue(), "DURATION_MS"));
            })
        .toList();
  }

  private static long unknownCount(List<Map<String, Object>> rows, String column) {
    return rows.stream().filter(row -> row.get(column) == null).count();
  }

  private static Long knownSum(List<Map<String, Object>> rows, String column) {
    var numbers =
        rows.stream()
            .map(row -> row.get(column))
            .filter(Number.class::isInstance)
            .map(Number.class::cast)
            .toList();
    return numbers.isEmpty() ? null : numbers.stream().mapToLong(Number::longValue).sum();
  }

  private static long knownSumOrZero(List<Map<String, Object>> rows, String column) {
    Long result = knownSum(rows, column);
    return result == null ? 0 : result;
  }
}
