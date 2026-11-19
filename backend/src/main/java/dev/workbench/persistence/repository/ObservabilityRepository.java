package dev.workbench.persistence.repository;

import dev.workbench.domain.StopCause;
import jakarta.persistence.EntityManager;
import java.util.List;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 观测聚合读取。
 *
 * <p>运行、事件和调用统计直接复用批次 1/2 已映射的实体，不再保留重复的表查询；评测批次和 Git 动作只读取告警、
 * 监控所需列，后续批次可在同一实体上扩展完整映射。
 */
@Repository
@Transactional(readOnly = true)
public class ObservabilityRepository {
  /** 与现有告警和运行停止统计口径一致；BLOCKED 不在本列表内。 */
  public static final List<String> STOPPED_STATUSES =
      List.of("LIMITED", "FAILED", "INTERRUPTED", "CANCELLED");

  private static final List<String> ALERT_GIT_STATUSES = List.of("UNKNOWN", "CONFLICT");

  private final EntityManager sessions;

  public ObservabilityRepository(EntityManager sessions) {
    this.sessions = sessions;
  }

  public List<StatusCount> runStatusCounts() {
    return statusCounts(
        "select r.status, count(r) from RunEntity r group by r.status order by r.status");
  }

  public List<StatusCount> agentStatusCounts() {
    List<?> rows =
        sessions
            .createNativeQuery(
                "select status, count(*) from agent_invocations group by status order by status")
            .getResultList();
    return rows.stream()
        .map(row -> (Object[]) row)
        .map(row -> new StatusCount((String) row[0], ((Number) row[1]).longValue()))
        .toList();
  }

  public List<StatusCount> modelStatusCounts() {
    return nativeStatusCounts("model_invocations");
  }

  public List<StatusCount> toolStatusCounts() {
    return nativeStatusCounts("tool_invocations");
  }

  public List<StatusCount> evaluationStatusCounts() {
    return statusCounts(
        "select c.status, count(c) from EvaluationCaseEntity c"
            + " group by c.status order by c.status");
  }

  public UsageTotals modelUsage() {
    Object[] row =
        (Object[])
            sessions
                .createQuery(
                    "select sum(m.inputTokens), count(m.inputTokens),"
                        + " count(m) - count(m.inputTokens), sum(m.outputTokens),"
                        + " count(m.outputTokens), count(m) - count(m.outputTokens),"
                        + " sum(m.durationMs), count(m.durationMs),"
                        + " count(m) - count(m.durationMs) from ModelInvocationEntity m")
                .getSingleResult();
    return new UsageTotals(
        nullableNumber(row[0]),
        number(row[1]),
        number(row[2]),
        nullableNumber(row[3]),
        number(row[4]),
        number(row[5]),
        nullableNumber(row[6]),
        number(row[7]),
        number(row[8]));
  }

  public DurationTotals toolUsage() {
    Object[] row =
        (Object[])
            sessions
                .createQuery(
                    "select sum(t.durationMs), count(t.durationMs),"
                        + " count(t) - count(t.durationMs) from ToolInvocationEntity t")
                .getSingleResult();
    return new DurationTotals(
        nullableNumber(row[0]), number(row[1]), number(row[2]));
  }

  public List<StopStatusCount> stoppedRunCounts() {
    List<Object[]> rows =
        sessions
            .createQuery(
                "select r.status, r.stopCause, count(r) from RunEntity r"
                    + " where r.status in :statuses group by r.status, r.stopCause",
                Object[].class)
            .setParameter("statuses", STOPPED_STATUSES)
            .getResultList();
    return rows.stream()
        .map(row -> new StopStatusCount((String) row[0], (String) row[1], number(row[2])))
        .toList();
  }

  public long countStoppedRunsWithoutStopCause() {
    return ((Number)
            sessions
                .createQuery(
                    "select count(r) from RunEntity r where r.status in :statuses"
                        + " and r.stopCause is null")
                .setParameter("statuses", STOPPED_STATUSES)
                .getSingleResult())
        .longValue();
  }

  /** 缺原因和原因与状态不一致分别计数；未知原因文本按不一致处理。 */
  public long countInconsistentStopCauses() {
    long total = 0;
    long missing = 0;
    long consistent = 0;
    for (var row : stoppedRunCounts()) {
      total += row.count();
      if (row.stopCause() == null) {
        missing += row.count();
        continue;
      }
      try {
        if (StopCause.valueOf(row.stopCause()).status().name().equals(row.status()))
          consistent += row.count();
      } catch (IllegalArgumentException ignored) {
        // 未识别编码保留为不一致，不能静默归入已知原因。
      }
    }
    return total - missing - consistent;
  }

  public long countRunsByStatus(String status) {
    return count(
        "select count(r) from RunEntity r where r.status = :status",
        "status",
        status);
  }

  public long countAgentInvocationsByStatus(String status) {
    return ((Number)
            sessions
                .createNativeQuery("select count(*) from agent_invocations where status = :status")
                .setParameter("status", status)
                .getSingleResult())
        .longValue();
  }

  public long countModelInvocationsByStatus(String status) {
    return nativeCountByStatus("model_invocations", status);
  }

  public long countToolInvocationsByStatus(String status) {
    return nativeCountByStatus("tool_invocations", status);
  }

  public long countEvaluationCasesByStatus(String status) {
    return count(
        "select count(c) from EvaluationCaseEntity c where c.status = :status",
        "status",
        status);
  }

  public long countRunsByStopCauseAndStatus(String stopCause, String status) {
    return ((Number)
            sessions
                .createQuery(
                    "select count(r) from RunEntity r where r.stopCause = :stopCause"
                        + " and r.status = :status")
                .setParameter("stopCause", stopCause)
                .setParameter("status", status)
                .getSingleResult())
        .longValue();
  }

  public long countCheckpointSkipped(String reason) {
    return ((Number)
            sessions
                .createQuery(
                    "select count(e) from EventEntity e where e.eventType = :type"
                        + " and e.reasonCode = :reason")
                .setParameter("type", "CHECKPOINT_SKIPPED")
                .setParameter("reason", reason)
                .getSingleResult())
        .longValue();
  }

  public long countCheckpointSkippedUnknown(List<String> knownReasons) {
    return ((Number)
            sessions
                .createQuery(
                    "select count(e) from EventEntity e where e.eventType = :type"
                        + " and (e.reasonCode is null or e.reasonCode not in :known)")
                .setParameter("type", "CHECKPOINT_SKIPPED")
                .setParameter("known", knownReasons)
                .getSingleResult())
        .longValue();
  }

  public long countRunsWithUnknownStatus(List<String> knownStatuses) {
    return countUnknownStatus(
        "select count(r) from RunEntity r where r.status is null or r.status not in :known",
        knownStatuses);
  }

  public long countAgentInvocationsWithUnknownStatus(List<String> knownStatuses) {
    return ((Number)
            sessions
                .createNativeQuery(
                    "select count(*) from agent_invocations"
                        + " where status is null or status not in (:known)")
                .setParameter("known", knownStatuses)
                .getSingleResult())
        .longValue();
  }

  public long countModelInvocationsWithUnknownStatus(List<String> knownStatuses) {
    return nativeCountUnknownStatus("model_invocations", knownStatuses);
  }

  public long countToolInvocationsWithUnknownStatus(List<String> knownStatuses) {
    return nativeCountUnknownStatus("tool_invocations", knownStatuses);
  }

  public long countEvaluationCasesWithUnknownStatus(List<String> knownStatuses) {
    return countUnknownStatus(
        "select count(c) from EvaluationCaseEntity c"
            + " where c.status is null or c.status not in :known",
        knownStatuses);
  }

  public List<AlertRunRow> recentAlertRuns(int limit) {
    List<Object[]> rows =
        sessions
            .createQuery(
                "select r.id, r.status, r.stopCause, r.createdAt from RunEntity r"
                    + " where r.status in :statuses order by r.createdAt desc",
                Object[].class)
            .setParameter("statuses", STOPPED_STATUSES)
            .setMaxResults(limit)
            .getResultList();
    return rows.stream()
        .map(row -> new AlertRunRow((String) row[0], (String) row[1], (String) row[2], (String) row[3]))
        .toList();
  }

  public List<AlertGitActionRow> recentAlertGitActions(int limit) {
    List<Object[]> rows =
        sessions
            .createQuery(
                "select a.id, a.kind, a.status, a.target, a.updatedAt from GitActionEntity a"
                    + " where a.status in :statuses order by a.updatedAt desc",
                Object[].class)
            .setParameter("statuses", ALERT_GIT_STATUSES)
            .setMaxResults(limit)
            .getResultList();
    return rows.stream()
        .map(
            row ->
                new AlertGitActionRow(
                    (String) row[0], (String) row[1], (String) row[2], (String) row[3], (String) row[4]))
        .toList();
  }

  public List<AlertBatchRow> recentFailedEvaluationBatches(int limit) {
    List<Object[]> rows =
        sessions
            .createQuery(
                "select b.id, b.suite, b.status, b.finishedAt from EvaluationBatchEntity b"
                    + " where b.status = :failed order by b.createdAt desc",
                Object[].class)
            .setParameter("failed", "FAILED")
            .setMaxResults(limit)
            .getResultList();
    return rows.stream()
        .map(row -> new AlertBatchRow((String) row[0], (String) row[1], (String) row[2], (String) row[3]))
        .toList();
  }

  private List<StatusCount> nativeStatusCounts(String table) {
    List<?> rows =
        sessions
            .createNativeQuery(
                "select status, count(*) from " + table + " group by status order by status")
            .getResultList();
    return rows.stream()
        .map(row -> (Object[]) row)
        .map(row -> new StatusCount((String) row[0], ((Number) row[1]).longValue()))
        .toList();
  }

  private long nativeCountByStatus(String table, String status) {
    return ((Number)
            sessions
                .createNativeQuery("select count(*) from " + table + " where status = :status")
                .setParameter("status", status)
                .getSingleResult())
        .longValue();
  }

  private long nativeCountUnknownStatus(String table, List<String> knownStatuses) {
    return ((Number)
            sessions
                .createNativeQuery(
                    "select count(*) from " + table
                        + " where status is null or status not in (:known)")
                .setParameter("known", knownStatuses)
                .getSingleResult())
        .longValue();
  }

  private List<StatusCount> statusCounts(String jpql) {
    return sessions.createQuery(jpql, Object[].class).getResultList().stream()
        .map(
            row ->
                new StatusCount(
                    row[0] instanceof Enum<?> value ? value.name() : (String) row[0],
                    number(row[1])))
        .toList();
  }

  private long countUnknownStatus(String jpql, List<String> knownStatuses) {
    return ((Number)
            sessions.createQuery(jpql).setParameter("known", knownStatuses).getSingleResult())
        .longValue();
  }

  private long count(String jpql, String parameter, Object value) {
    return ((Number)
            sessions.createQuery(jpql).setParameter(parameter, value).getSingleResult())
        .longValue();
  }

  private static long number(Object value) {
    return ((Number) value).longValue();
  }

  private static Long nullableNumber(Object value) {
    return value == null ? null : ((Number) value).longValue();
  }

  public record StatusCount(String status, long count) {}

  public record StopStatusCount(String status, String stopCause, long count) {}

  public record UsageTotals(
      Long inputSum,
      long inputKnownCalls,
      long inputUnknownCalls,
      Long outputSum,
      long outputKnownCalls,
      long outputUnknownCalls,
      Long durationSum,
      long durationKnownCalls,
      long durationUnknownCalls) {}

  public record DurationTotals(Long durationSum, long durationKnownCalls, long durationUnknownCalls) {}

  public record AlertRunRow(String id, String status, String stopCause, String createdAt) {}

  public record AlertGitActionRow(
      String id, String kind, String status, String target, String updatedAt) {}

  public record AlertBatchRow(String id, String suite, String status, String finishedAt) {}
}
