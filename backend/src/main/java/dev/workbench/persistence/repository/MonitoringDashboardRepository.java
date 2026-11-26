package dev.workbench.persistence.repository;

import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 细粒度运行监控与故障诊断的只读证据查询。
 *
 * <p>不在这里做业务聚合：只把运行、调用、节点、安检门轨迹和交接等原始事实投影成明确记录，
 * 由 observability 服务统一计算比例、分桶与排查结论。
 */
@Repository
@Transactional(readOnly = true)
public class MonitoringDashboardRepository {
  private final EntityManager sessions;

  public MonitoringDashboardRepository(EntityManager sessions) {
    this.sessions = sessions;
  }

  /** 读取指定任务集合在时间范围内的运行；from 为空表示不限起始时间。 */
  public List<RunRow> runs(List<String> taskIds, String from) {
    if (taskIds == null || taskIds.isEmpty()) return List.of();
    List<Object[]> rows =
        sessions
            .createQuery(
                """
                select r.id, r.taskId, t.goal, r.status, r.reason, r.stopCause,
                  r.createdAt, r.updatedAt, r.elapsedMs, t.modelMode, t.workspaceId
                from RunEntity r join TaskEntity t on t.id = r.taskId
                where r.taskId in :taskIds and (:from is null or r.createdAt >= :from)
                order by r.createdAt desc
                """,
                Object[].class)
            .setParameter("taskIds", taskIds)
            .setParameter("from", from)
            .getResultList();
    return rows.stream()
        .map(
            row ->
                new RunRow(
                    (String) row[0],
                    (String) row[1],
                    (String) row[2],
                    (String) row[3],
                    (String) row[4],
                    (String) row[5],
                    (String) row[6],
                    (String) row[7],
                    number(row[8]),
                    (String) row[9],
                    (String) row[10]))
        .toList();
  }

  /** 按运行标识读取单条运行；不在这里做归属校验。 */
  public RunRow findRun(String runId) {
    List<Object[]> rows =
        sessions
            .createQuery(
                """
                select r.id, r.taskId, t.goal, r.status, r.reason, r.stopCause,
                  r.createdAt, r.updatedAt, r.elapsedMs, t.modelMode, t.workspaceId
                from RunEntity r join TaskEntity t on t.id = r.taskId
                where r.id = :runId
                """,
                Object[].class)
            .setParameter("runId", runId)
            .getResultList();
    if (rows.isEmpty()) return null;
    Object[] row = rows.getFirst();
    return new RunRow(
        (String) row[0],
        (String) row[1],
        (String) row[2],
        (String) row[3],
        (String) row[4],
        (String) row[5],
        (String) row[6],
        (String) row[7],
        number(row[8]),
        (String) row[9],
        (String) row[10]);
  }

  /**
   * 任务筛选选项：可读任务的目标、最新一轮状态、最近活跃时间与运行总数。
   *
   * <p>只看任务与运行元数据，不读取任何提示词、模型输出或文件正文；按最近活跃排序并有硬上限，供监控页筛选下拉直接消费。
   */
  public List<TaskOptionRow> taskOptions(List<String> taskIds, int limit) {
    if (taskIds == null || taskIds.isEmpty()) return List.of();
    List<Object[]> rows =
        sessions
            .createQuery(
                """
                select t.id, t.goal, r.status, r.createdAt
                from TaskEntity t
                left join RunEntity r on r.taskId = t.id and not exists (
                  select 1 from RunEntity newer where newer.taskId = r.taskId
                    and (newer.createdAt > r.createdAt
                      or (newer.createdAt = r.createdAt and newer.id > r.id)))
                where t.id in :taskIds
                """,
                Object[].class)
            .setParameter("taskIds", taskIds)
            .getResultList();
    Map<String, Long> counts = new java.util.LinkedHashMap<>();
    for (Object[] row :
        sessions
            .createQuery(
                "select r.taskId, count(r) from RunEntity r where r.taskId in :taskIds"
                    + " group by r.taskId",
                Object[].class)
            .setParameter("taskIds", taskIds)
            .getResultList())
      counts.put((String) row[0], number(row[1]));
    return rows.stream()
        .map(
            row ->
                new TaskOptionRow(
                    (String) row[0],
                    (String) row[1],
                    (String) row[2],
                    (String) row[3],
                    counts.getOrDefault((String) row[0], 0L)))
        .sorted(
            java.util.Comparator.comparing(
                TaskOptionRow::latestAt,
                java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder())))
        .limit(Math.max(1, limit))
        .toList();
  }

  /** 任务筛选选项的原始投影；latestAt 只用于排序，不下发给前端。 */
  public record TaskOptionRow(
      String taskId, String goal, String latestStatus, String latestAt, long runCount) {}

  public List<ModelCallRow> modelCalls(List<String> runIds) {    if (runIds == null || runIds.isEmpty()) return List.of();
    List<Object[]> rows =
        sessions
            .createQuery(
                """
                select m.id, m.runId, m.agentId, m.modelName, cast(m.status as String),
                  m.inputTokens, m.outputTokens, m.durationMs, m.errorMessage, m.createdAt
                from ModelInvocationEntity m where m.runId in :runIds order by m.createdAt
                """,
                Object[].class)
            .setParameter("runIds", runIds)
            .getResultList();
    return rows.stream()
        .map(
            row ->
                new ModelCallRow(
                    (String) row[0],
                    (String) row[1],
                    (String) row[2],
                    (String) row[3],
                    (String) row[4],
                    nullableInt(row[5]),
                    nullableInt(row[6]),
                    nullableLong(row[7]),
                    (String) row[8],
                    (String) row[9]))
        .toList();
  }

  public List<ToolCallRow> toolCalls(List<String> runIds) {
    if (runIds == null || runIds.isEmpty()) return List.of();
    List<Object[]> rows =
        sessions
            .createQuery(
                """
                select t.id, t.runId, t.agentId, t.toolName, cast(t.status as String),
                  t.errorMessage, t.durationMs, t.createdAt
                from ToolInvocationEntity t where t.runId in :runIds order by t.createdAt
                """,
                Object[].class)
            .setParameter("runIds", runIds)
            .getResultList();
    return rows.stream()
        .map(
            row ->
                new ToolCallRow(
                    (String) row[0],
                    (String) row[1],
                    (String) row[2],
                    (String) row[3],
                    (String) row[4],
                    (String) row[5],
                    nullableLong(row[6]),
                    (String) row[7]))
        .toList();
  }

  public List<NodeRow> nodes(List<String> runIds) {
    if (runIds == null || runIds.isEmpty()) return List.of();
    List<Object[]> rows =
        sessions
            .createQuery(
                """
                select n.id, n.runId, n.nodeLabel, n.nodeKey,
                  coalesce(n.permissionRole, n.roleCode), cast(n.status as String),
                  n.agentId, n.startedAt, n.finishedAt, n.workspaceWrite,
                  cast(n.nodeKind as String)
                from PlanNodeEntity n where n.runId in :runIds order by n.sequenceNo
                """,
                Object[].class)
            .setParameter("runIds", runIds)
            .getResultList();
    return rows.stream()
        .map(
            row -> {
              String label = (String) row[2];
              if (label == null || label.isBlank()) label = (String) row[3];
              return new NodeRow(
                  (String) row[0],
                  (String) row[1],
                  label,
                  (String) row[4],
                  (String) row[5],
                  (String) row[6],
                  (String) row[7],
                  (String) row[8],
                  Boolean.TRUE.equals(row[9]),
                  (String) row[10]);
            })
        .toList();
  }

  public List<GatewayTraceRow> gatewayTraces(List<String> runIds) {
    if (runIds == null || runIds.isEmpty()) return List.of();
    List<Object[]> rows =
        sessions
            .createQuery(
                """
                select t.toolCallId, t.runId, t.gatewayChecksJson
                from ToolCallTraceEntity t where t.runId in :runIds
                """,
                Object[].class)
            .setParameter("runIds", runIds)
            .getResultList();
    return rows.stream()
        .map(
            row ->
                new GatewayTraceRow(
                    (String) row[0], (String) row[1], (String) row[2]))
        .toList();
  }

  public List<PayloadRow> modelPayloads(String runId) {
    List<Object[]> rows =
        sessions
            .createQuery(
                "select p.modelCallId, p.truncated from ModelCallPayloadEntity p where p.runId = :runId",
                Object[].class)
            .setParameter("runId", runId)
            .getResultList();
    return rows.stream()
        .map(row -> new PayloadRow((String) row[0], Boolean.TRUE.equals(row[1])))
        .toList();
  }

  /** 读取运行内的交接与接收事实；没有接收记录时 receivedAt 为空。 */
  public List<HandoffRow> handoffs(String runId) {
    List<Object[]> rows =
        sessions
            .createQuery(
                """
                select h.id, h.agentId, h.direction, h.createdAt, r.receivedAt
                from HandoffEntity h
                left join HandoffReceiptEntity r on r.handoffId = h.id and r.runId = h.runId
                where h.runId = :runId order by h.createdAt, h.id
                """,
                Object[].class)
            .setParameter("runId", runId)
            .getResultList();
    return rows.stream()
        .map(
            row ->
                new HandoffRow(
                    (String) row[0],
                    (String) row[1],
                    (String) row[2],
                    (String) row[3],
                    (String) row[4]))
        .toList();
  }

  public List<AssemblyRow> assemblies(List<String> agentIds) {
    if (agentIds == null || agentIds.isEmpty()) return List.of();
    List<Object[]> rows =
        sessions
            .createQuery(
                "select a.agentId, a.payload from AgentAssemblyEntity a where a.agentId in :agentIds",
                Object[].class)
            .setParameter("agentIds", agentIds)
            .getResultList();
    return rows.stream()
        .map(row -> new AssemblyRow((String) row[0], (String) row[1]))
        .toList();
  }

  public List<GitActionRow> gitActions(String workspaceId) {
    if (workspaceId == null || workspaceId.isBlank()) return List.of();
    List<Object[]> rows =
        sessions
            .createQuery(
                """
                select a.kind, a.status, a.detail, a.updatedAt from GitActionEntity a
                where a.workspaceId = :workspaceId order by a.createdAt desc
                """,
                Object[].class)
            .setParameter("workspaceId", workspaceId)
            .getResultList();
    return rows.stream()
        .map(
            row ->
                new GitActionRow(
                    (String) row[0], (String) row[1], (String) row[2], (String) row[3]))
        .toList();
  }

  public List<EventRow> recentEvents(String runId, int limit) {
    List<Object[]> rows =
        sessions
            .createQuery(
                """
                select e.eventType, e.reasonCode, e.message, e.nodeId, e.createdAt
                from EventEntity e where e.runId = :runId order by e.id desc
                """,
                Object[].class)
            .setParameter("runId", runId)
            .setMaxResults(limit)
            .getResultList();
    return rows.stream()
        .map(
            row ->
                new EventRow(
                    (String) row[0],
                    (String) row[1],
                    (String) row[2],
                    (String) row[3],
                    (String) row[4]))
        .toList();
  }

  public java.util.Set<String> deliveredWorkspaceIds() {
    return new java.util.HashSet<>(
        sessions
            .createQuery(
                "select distinct a.workspaceId from GitActionEntity a where a.status = 'CONFIRMED'",
                String.class)
            .getResultList());
  }

  /** 费用台账：已结算与仍在预留的金额分别汇总，避免把未知费用当成零。 */
  public CostTotals costTotals(List<String> runIds) {
    if (runIds == null || runIds.isEmpty()) return new CostTotals(0L, 0L, 0L);
    Object[] row =
        (Object[])
            sessions
                .createQuery(
                    """
                    select coalesce(sum(c.settledMicros), 0), coalesce(sum(c.reservedMicros), 0),
                      count(c)
                    from RunCostLimitEntity c where c.runId in :runIds
                    """)
                .setParameter("runIds", runIds)
                .getSingleResult();
    return new CostTotals(number(row[0]), number(row[1]), number(row[2]));
  }

  private static long number(Object value) {
    return value == null ? 0L : ((Number) value).longValue();
  }

  private static Long nullableLong(Object value) {
    return value == null ? null : ((Number) value).longValue();
  }

  private static Integer nullableInt(Object value) {
    return value == null ? null : ((Number) value).intValue();
  }

  public record RunRow(
      String id,
      String taskId,
      String taskGoal,
      String status,
      String reason,
      String stopCause,
      String createdAt,
      String updatedAt,
      long elapsedMs,
      String modelMode,
      String workspaceId) {}

  public record ModelCallRow(
      String id,
      String runId,
      String agentId,
      String modelName,
      String status,
      Integer inputTokens,
      Integer outputTokens,
      Long durationMs,
      String errorMessage,
      String createdAt) {}

  public record ToolCallRow(
      String id,
      String runId,
      String agentId,
      String toolName,
      String status,
      String errorMessage,
      Long durationMs,
      String createdAt) {}

  public record NodeRow(
      String id,
      String runId,
      String label,
      String role,
      String status,
      String agentId,
      String startedAt,
      String finishedAt,
      boolean workspaceWrite,
      String kind) {}

  public record GatewayTraceRow(String toolCallId, String runId, String gatewayChecksJson) {}

  public record PayloadRow(String modelCallId, boolean truncated) {}

  public record HandoffRow(
      String id, String agentId, String direction, String createdAt, String receivedAt) {}

  public record AssemblyRow(String agentId, String payload) {}

  public record GitActionRow(String kind, String status, String detail, String updatedAt) {}

  public record EventRow(
      String eventType, String reasonCode, String message, String nodeId, String createdAt) {}

  public record CostTotals(long settledMicros, long reservedMicros, long runsWithCost) {
    public BigDecimal estimatedAmount() {
      if (runsWithCost == 0) return null;
      return BigDecimal.valueOf(settledMicros, 6);
    }

    public BigDecimal reservedAmount() {
      if (runsWithCost == 0) return null;
      return BigDecimal.valueOf(reservedMicros, 6);
    }
  }
}
