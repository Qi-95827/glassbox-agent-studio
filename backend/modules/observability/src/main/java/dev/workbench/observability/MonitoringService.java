package dev.workbench.observability;

import dev.workbench.domain.InvocationStatus;
import dev.workbench.domain.RunStatus;
import dev.workbench.domain.StopCause;
import dev.workbench.evaluation.CaseStatus;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 查询低敏运行概况；SQL 表名固定，避免接收用户提供的查询表达式。 */
@Service
public class MonitoringService {
  private final JdbcTemplate db;

  public MonitoringService(JdbcTemplate db) {
    this.db = db;
  }

  public MonitoringOverview overview() {
    var runs =
        db.query(
            "SELECT status, COUNT(*) AS n FROM runs GROUP BY status ORDER BY status",
            (r, i) -> {
              String raw = r.getString("status");
              RunStatus parsed = parseStatus(RunStatus.class, raw);
              return new MonitoringOverview.RunCount(
                  parsed, parsed == null ? raw : null, r.getLong("n"));
            });
    var agents = invocations("agent_invocations");
    var models = invocations("model_invocations");
    var tools = invocations("tool_invocations");
    var cases =
        db.query(
            "SELECT status, COUNT(*) AS n FROM evaluation_cases GROUP BY status ORDER BY status",
            (r, i) -> {
              String raw = r.getString("status");
              CaseStatus parsed = parseStatus(CaseStatus.class, raw);
              return new MonitoringOverview.CaseCount(
                  parsed, parsed == null ? raw : null, r.getLong("n"));
            });
    var modelUsage =
        db.queryForMap(
            "SELECT SUM(input_tokens) AS input_sum, COUNT(*)-COUNT(input_tokens) AS input_unknown,"
                + " SUM(output_tokens) AS output_sum, COUNT(*)-COUNT(output_tokens) AS"
                + " output_unknown, SUM(duration_ms) AS duration_sum, COUNT(*)-COUNT(duration_ms)"
                + " AS duration_unknown FROM model_invocations");
    var toolUsage =
        db.queryForMap(
            "SELECT SUM(duration_ms) AS duration_sum, COUNT(*)-COUNT(duration_ms) AS"
                + " duration_unknown FROM tool_invocations");
    var usage =
        new MonitoringOverview.Usage(
            numberOrNull(modelUsage.get("INPUT_SUM")),
            ((Number) modelUsage.get("INPUT_UNKNOWN")).longValue(),
            numberOrNull(modelUsage.get("OUTPUT_SUM")),
            ((Number) modelUsage.get("OUTPUT_UNKNOWN")).longValue(),
            numberOrNull(modelUsage.get("DURATION_SUM")),
            ((Number) modelUsage.get("DURATION_UNKNOWN")).longValue(),
            numberOrNull(toolUsage.get("DURATION_SUM")),
            ((Number) toolUsage.get("DURATION_UNKNOWN")).longValue());
    var groupedStops =
        db.queryForList(
            "SELECT status,stop_cause,COUNT(*) AS n FROM runs WHERE status IN"
                + " ('LIMITED','FAILED','INTERRUPTED','CANCELLED') GROUP BY status,stop_cause");
    var counts = new java.util.EnumMap<StopCause, Long>(StopCause.class);
    long missing = 0, inconsistent = 0;
    for (var row : groupedStops) {
      long count = ((Number) row.get("N")).longValue();
      Object raw = row.get("STOP_CAUSE");
      if (raw == null) {
        missing += count;
        continue;
      }
      StopCause cause = parseStatus(StopCause.class, raw.toString());
      if (cause == null || !cause.status().name().equals(row.get("STATUS"))) {
        inconsistent += count;
        continue;
      }
      counts.merge(cause, count, Long::sum);
    }
    var stops =
        counts.entrySet().stream()
            .map(e -> new MonitoringOverview.StopCount(e.getKey(), e.getValue()))
            .toList();
    return new MonitoringOverview(
        runs, agents, models, tools, cases, stops, missing, inconsistent, usage);
  }

  private List<MonitoringOverview.InvocationCount> invocations(String table) {
    return db.query(
        "SELECT status, COUNT(*) AS n FROM " + table + " GROUP BY status ORDER BY status",
        (r, i) -> {
          String raw = r.getString("status");
          InvocationStatus parsed = parseStatus(InvocationStatus.class, raw);
          return new MonitoringOverview.InvocationCount(
              parsed, parsed == null ? raw : null, r.getLong("n"));
        });
  }

  private static <T extends Enum<T>> T parseStatus(Class<T> type, String raw) {
    if (raw == null) return null;
    try {
      return Enum.valueOf(type, raw);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private static Long numberOrNull(Object value) {
    return value == null ? null : ((Number) value).longValue();
  }
}
