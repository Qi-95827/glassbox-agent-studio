package dev.workbench.maintenance;

import dev.workbench.domain.RunStatus;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 冷数据 TTL 清理：只清理「已过期且所属运行已终态」的大对象，绝不触碰审计主干。
 *
 * <p>硬红线：{@code tasks} / {@code runs} / {@code events} 永不删除；任何 {@code PENDING} / {@code RUNNING} /
 * {@code PAUSED} 活跃运行的载荷、产物与会话一律保留。所有 SQL 只用标准 SQL 子查询与参数占位，H2 与 PostgreSQL 通用，
 * 因此本包不新增任何迁移脚本。
 *
 * <p>默认每天 03:30 触发一次；可通过 {@code workbench.retention.*} 关闭或调整保留天数。
 */
@Service
public class DataRetentionService {
  private static final Logger LOG = LoggerFactory.getLogger(DataRetentionService.class);

  /** 允许清理的冷数据表白名单；表名不接受外部输入。 */
  private static final java.util.Set<String> COLD_OBJECT_TABLES =
      java.util.Set.of("context_artifacts", "model_call_payloads", "tool_call_traces");

  /** 视为活跃（未终态）的运行状态集合；这些运行的冷数据永不清理。 */
  private static final List<String> ACTIVE_RUN_STATUSES =
      List.of(
          RunStatus.PENDING.name(),
          RunStatus.RUNNING.name(),
          RunStatus.PAUSED.name());

  private final JdbcTemplate jdbc;
  private final boolean enabled;
  private final int payloadDays;
  private final int artifactDays;
  private final int sessionDays;

  public DataRetentionService(
      JdbcTemplate jdbc,
      @Value("${workbench.retention.enabled:true}") boolean enabled,
      @Value("${workbench.retention.payload-days:30}") int payloadDays,
      @Value("${workbench.retention.artifact-days:30}") int artifactDays,
      @Value("${workbench.retention.session-days:14}") int sessionDays) {
    if (payloadDays < 1 || artifactDays < 1 || sessionDays < 1)
      throw new IllegalArgumentException("冷数据保留天数必须为不小于 1 的整数。");
    this.jdbc = jdbc;
    this.enabled = enabled;
    this.payloadDays = payloadDays;
    this.artifactDays = artifactDays;
    this.sessionDays = sessionDays;
  }

  /** 清理结果汇总；字段是本次真实删除的行数。 */
  public record RetentionReport(
      int deletedPayloads, int deletedToolTraces, int deletedArtifacts, int deletedSessions) {}

  /** 定时入口：仅在启用时执行，且异常不向调度线程外抛，避免一次失败终止之后的全部调度。 */
  @Scheduled(cron = "${workbench.retention.cron:0 30 3 * * *}")
  public void scheduledPurge() {
    if (!enabled) return;
    try {
      var report = purgeExpired();
      LOG.info(
          "冷数据归档完成：载荷 {} 条、工具轨迹 {} 条、上下文产物 {} 条、Agent 会话 {} 条。",
          report.deletedPayloads(),
          report.deletedToolTraces(),
          report.deletedArtifacts(),
          report.deletedSessions());
    } catch (RuntimeException failure) {
      LOG.warn("冷数据归档本轮失败，已跳过且不影响运行数据：{}", failure.getClass().getSimpleName());
    }
  }

  /**
   * 执行一次过期清理。
   *
   * <p>只用「运行已终态或没有运行归属」作为删除前提；活跃运行的数据无条件保留。
   */
  @Transactional
  public RetentionReport purgeExpired() {
    if (!enabled) return new RetentionReport(0, 0, 0, 0);
    Instant now = Instant.now();
    String payloadCutoff = now.minus(payloadDays, ChronoUnit.DAYS).toString();
    String artifactCutoff = now.minus(artifactDays, ChronoUnit.DAYS).toString();
    LocalDateTime sessionCutoff = LocalDateTime.now().minusDays(sessionDays);

    int artifacts = deleteColdRows("context_artifacts", artifactCutoff);
    int payloads = deleteColdRows("model_call_payloads", payloadCutoff);
    int toolTraces = deleteColdRows("tool_call_traces", payloadCutoff);

    // Agent 会话 updated_at 为 TIMESTAMP 列，直接使用 LocalDateTime；同时跳过仍归属活跃运行实例的会话。
    int sessions =
        jdbc.update(
            "DELETE FROM agentscope_sessions"
                + " WHERE updated_at < ?"
                + " AND NOT EXISTS ("
                + "SELECT 1 FROM agent_invocations a JOIN runs r ON r.id = a.run_id"
                + " WHERE r.status IN (?, ?, ?)"
                + " AND (agentscope_sessions.session_id = a.id"
                + " OR agentscope_sessions.session_id LIKE CONCAT('%:', a.id)))",
            sessionCutoff,
            ACTIVE_RUN_STATUSES.get(0),
            ACTIVE_RUN_STATUSES.get(1),
            ACTIVE_RUN_STATUSES.get(2));

    return new RetentionReport(payloads, toolTraces, artifacts, sessions);
  }

  /**
   * 删除指定表中「已过期且不关联活跃运行」的行。
   *
   * <p>表名来自本类内的固定常量集合（不接受外部输入）；活跃状态用参数占位，H2 与 PostgreSQL 语义一致。
   */
  private int deleteColdRows(String table, String cutoff) {
    if (!COLD_OBJECT_TABLES.contains(table))
      throw new IllegalArgumentException("不支持的冷数据表：" + table);
    return jdbc.update(
        "DELETE FROM "
            + table
            + " WHERE created_at < ?"
            + " AND (run_id IS NULL OR NOT EXISTS"
            + " (SELECT 1 FROM runs r WHERE r.id = "
            + table
            + ".run_id AND r.status IN (?, ?, ?)))",
        cutoff,
        ACTIVE_RUN_STATUSES.get(0),
        ACTIVE_RUN_STATUSES.get(1),
        ACTIVE_RUN_STATUSES.get(2));
  }
}
