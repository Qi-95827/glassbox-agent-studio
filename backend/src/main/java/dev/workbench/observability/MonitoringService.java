package dev.workbench.observability;

import dev.workbench.domain.InvocationStatus;
import dev.workbench.domain.RunStatus;
import dev.workbench.domain.StopCause;
import dev.workbench.evaluation.CaseStatus;
import dev.workbench.persistence.repository.ObservabilityRepository;
import java.util.List;
import org.springframework.stereotype.Service;

/** 查询低敏运行概况；聚合全部通过已映射实体的 JPQL 完成。 */
@Service
public class MonitoringService {
  private final ObservabilityRepository rows;

  public MonitoringService(ObservabilityRepository rows) {
    this.rows = rows;
  }

  public MonitoringOverview overview() {
    var runs =
        rows.runStatusCounts().stream()
            .map(
                row -> {
                  RunStatus parsed = parseStatus(RunStatus.class, row.status());
                  return new MonitoringOverview.RunCount(
                      parsed, parsed == null ? row.status() : null, row.count());
                })
            .toList();
    var agents = invocations(rows.agentStatusCounts());
    var models = invocations(rows.modelStatusCounts());
    var tools = invocations(rows.toolStatusCounts());
    var cases =
        rows.evaluationStatusCounts().stream()
            .map(
                row -> {
                  CaseStatus parsed = parseStatus(CaseStatus.class, row.status());
                  return new MonitoringOverview.CaseCount(
                      parsed, parsed == null ? row.status() : null, row.count());
                })
            .toList();
    var modelUsage = rows.modelUsage();
    var toolUsage = rows.toolUsage();
    var usage =
        new MonitoringOverview.Usage(
            modelUsage.inputSum(),
            modelUsage.inputUnknownCalls(),
            modelUsage.outputSum(),
            modelUsage.outputUnknownCalls(),
            modelUsage.durationSum(),
            modelUsage.durationUnknownCalls(),
            toolUsage.durationSum(),
            toolUsage.durationUnknownCalls());
    var counts = new java.util.EnumMap<StopCause, Long>(StopCause.class);
    long missing = 0, inconsistent = 0;
    for (var row : rows.stoppedRunCounts()) {
      long count = row.count();
      String raw = row.stopCause();
      if (raw == null) {
        missing += count;
        continue;
      }
      StopCause cause = parseStatus(StopCause.class, raw);
      if (cause == null || !cause.status().name().equals(row.status())) {
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

  private static List<MonitoringOverview.InvocationCount> invocations(
      List<ObservabilityRepository.StatusCount> rows) {
    return rows.stream()
        .map(
            row -> {
              InvocationStatus parsed = parseStatus(InvocationStatus.class, row.status());
              return new MonitoringOverview.InvocationCount(
                  parsed, parsed == null ? row.status() : null, row.count());
            })
        .toList();
  }

  private static <T extends Enum<T>> T parseStatus(Class<T> type, String raw) {
    if (raw == null) return null;
    try {
      return Enum.valueOf(type, raw);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }
}
