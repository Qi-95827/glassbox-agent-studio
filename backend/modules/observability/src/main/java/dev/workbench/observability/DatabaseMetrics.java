package dev.workbench.observability;

import dev.workbench.domain.CheckpointSkipReason;
import dev.workbench.domain.InvocationStatus;
import dev.workbench.domain.RunStatus;
import dev.workbench.domain.StopCause;
import dev.workbench.evaluation.CaseStatus;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 数据库快照指标；固定标签不包含任务 ID、用户信息或代码内容。 */
@Component
public class DatabaseMetrics implements MeterBinder {
  private final JdbcTemplate db;

  public DatabaseMetrics(JdbcTemplate db) {
    this.db = db;
  }

  @Override
  public void bindTo(MeterRegistry registry) {
    for (RunStatus status : RunStatus.values()) {
      register(
          registry,
          "workbench.runs",
          "status",
          status.name(),
          "SELECT COUNT(*) FROM runs WHERE status=?",
          status.name());
    }
    for (InvocationStatus status : InvocationStatus.values()) {
      registerInvocation(registry, "agent", "agent_invocations", status);
      registerInvocation(registry, "model", "model_invocations", status);
      registerInvocation(registry, "tool", "tool_invocations", status);
    }
    for (CaseStatus status : CaseStatus.values()) {
      register(
          registry,
          "workbench.evaluation.cases",
          "status",
          status.name(),
          "SELECT COUNT(*) FROM evaluation_cases WHERE status=?",
          status.name());
    }
    for (StopCause cause : StopCause.values()) {
      register(
          registry,
          "workbench.run.stop.cause",
          "cause",
          cause.name(),
          "SELECT COUNT(*) FROM runs WHERE stop_cause=? AND status=?",
          cause.name(),
          cause.status().name());
    }
    String stopped = " status IN ('LIMITED','FAILED','INTERRUPTED','CANCELLED') ";
    register(
        registry,
        "workbench.run.stop.cause",
        "cause",
        "UNRECORDED",
        "SELECT COUNT(*) FROM runs WHERE " + stopped + " AND stop_cause IS NULL");
    var clauses =
        java.util.Arrays.stream(StopCause.values())
            .map(c -> "(stop_cause=? AND status=?)")
            .toList();
    var args =
        java.util.Arrays.stream(StopCause.values())
            .flatMap(c -> java.util.stream.Stream.of(c.name(), c.status().name()))
            .toArray();
    register(
        registry,
        "workbench.run.stop.cause",
        "cause",
        "INCONSISTENT",
        "SELECT COUNT(*) FROM runs WHERE "
            + stopped
            + " AND stop_cause IS NOT NULL AND NOT ("
            + String.join(" OR ", clauses)
            + ")",
        args);
    for (CheckpointSkipReason reason : CheckpointSkipReason.values()) {
      register(
          registry,
          "workbench.checkpoint.skipped",
          "reason",
          reason.name(),
          "SELECT COUNT(*) FROM events WHERE event_type='CHECKPOINT_SKIPPED' AND reason_code=?",
          reason.name());
    }
    String reasonParameters =
        String.join(",", java.util.Collections.nCopies(CheckpointSkipReason.values().length, "?"));
    Object[] reasonCodes =
        java.util.Arrays.stream(CheckpointSkipReason.values()).map(Enum::name).toArray();
    register(
        registry,
        "workbench.checkpoint.skipped",
        "reason",
        "UNKNOWN",
        "SELECT COUNT(*) FROM events WHERE event_type='CHECKPOINT_SKIPPED' AND (reason_code IS"
            + " NULL OR reason_code NOT IN ("
            + reasonParameters
            + "))",
        reasonCodes);
    registerTokenMetrics(registry, "input", "input_tokens");
    registerTokenMetrics(registry, "output", "output_tokens");
    registerDurationMetrics(registry, "model", "model_invocations");
    registerDurationMetrics(registry, "tool", "tool_invocations");
    registerUnknownStatuses(registry, "run", "runs", RunStatus.values());
    registerUnknownStatuses(registry, "agent", "agent_invocations", InvocationStatus.values());
    registerUnknownStatuses(registry, "model", "model_invocations", InvocationStatus.values());
    registerUnknownStatuses(registry, "tool", "tool_invocations", InvocationStatus.values());
    registerUnknownStatuses(registry, "evaluation", "evaluation_cases", CaseStatus.values());
  }

  /** 只暴露未知数量，不把数据库中的未知编码变为高基数标签。 */
  private void registerUnknownStatuses(
      MeterRegistry registry, String kind, String table, Enum<?>[] known) {
    String parameters = String.join(",", java.util.Collections.nCopies(known.length, "?"));
    Object[] values = java.util.Arrays.stream(known).map(Enum::name).toArray();
    register(
        registry,
        "workbench.status.unknown",
        "kind",
        kind,
        "SELECT COUNT(*) FROM "
            + table
            + " WHERE status IS NULL OR status NOT IN ("
            + parameters
            + ")",
        values);
  }

  private void registerInvocation(
      MeterRegistry registry, String kind, String table, InvocationStatus status) {
    Gauge.builder(
            "workbench.invocations",
            db,
            ignored -> number("SELECT COUNT(*) FROM " + table + " WHERE status=?", status.name()))
        .description("当前保留记录中的实际调用数")
        .tag("kind", kind)
        .tag("status", status.name())
        .register(registry);
  }

  private void registerTokenMetrics(MeterRegistry registry, String direction, String column) {
    register(
        registry,
        "workbench.model.tokens.known",
        "direction",
        direction,
        "SELECT COALESCE(SUM(" + column + "),0) FROM model_invocations");
    register(
        registry,
        "workbench.model.tokens.known.calls",
        "direction",
        direction,
        "SELECT COUNT(*) FROM model_invocations WHERE " + column + " IS NOT NULL");
    register(
        registry,
        "workbench.model.tokens.unknown.calls",
        "direction",
        direction,
        "SELECT COUNT(*) FROM model_invocations WHERE " + column + " IS NULL");
  }

  private void registerDurationMetrics(MeterRegistry registry, String kind, String table) {
    Gauge.builder(
            "workbench.invocation.duration.known.seconds",
            db,
            ignored -> number("SELECT COALESCE(SUM(duration_ms),0) FROM " + table) / 1000.0)
        .tag("kind", kind)
        .register(registry);
    register(
        registry,
        "workbench.invocation.duration.unknown.calls",
        "kind",
        kind,
        "SELECT COUNT(*) FROM " + table + " WHERE duration_ms IS NULL");
  }

  private void register(
      MeterRegistry registry, String name, String tag, String value, String sql, Object... args) {
    Gauge.builder(name, db, ignored -> number(sql, args)).tag(tag, value).register(registry);
  }

  private double number(String sql, Object... args) {
    Number value = db.queryForObject(sql, Number.class, args);
    if (value == null) throw new IllegalStateException("指标查询无结果。");
    return value.doubleValue();
  }
}
