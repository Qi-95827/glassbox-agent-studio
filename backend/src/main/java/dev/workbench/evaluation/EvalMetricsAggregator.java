package dev.workbench.evaluation;

import dev.workbench.framework.evaluation.EvalCategory;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** 把逐题真实结果聚合为六维雷达与批次 KPI；不做任何平滑或补分。 */
@Component
public class EvalMetricsAggregator {

  public record CaseRow(
      EvalCategory category,
      String sampleId,
      int repeatIndex,
      boolean passed,
      double codeScore,
      long durationMs,
      double costUsd,
      Map<String, Object> metrics) {}

  public Map<String, Object> aggregate(List<CaseRow> rows, int repeatK, boolean budgetStopped) {
    var summary = new LinkedHashMap<String, Object>();
    var categories = new LinkedHashMap<String, Object>();
    for (EvalCategory category : EvalCategory.values()) {
      var categoryRows =
          rows.stream().filter(row -> row.category() == category).toList();
      categories.put(category.name(), categorySummary(categoryRows, repeatK));
    }
    int total = rows.size();
    int passed = (int) rows.stream().filter(CaseRow::passed).count();
    double score = rows.isEmpty() ? 0 : rows.stream().mapToDouble(CaseRow::codeScore).average().orElse(0);
    double cost = rows.stream().mapToDouble(CaseRow::costUsd).sum();
    var overall = new LinkedHashMap<String, Object>();
    overall.put("total", total);
    overall.put("passed", passed);
    overall.put("passRate", ratio(passed, total));
    overall.put("score", round(score * 100));
    overall.put("totalCostUsd", round(cost));
    overall.put("avgCostUsd", total == 0 ? 0 : round(cost / total));
    overall.put("p95DurationMs", p95(rows));
    overall.put("safetyBlockRate", metricAverage(rows, EvalCategory.SAFETY_INJECTION, "blockRate"));
    overall.put(
        "avgNeedleRetention", metricAverage(rows, EvalCategory.CONTEXT_COMPACTION, "needleRetention"));
    overall.put("avgRecallAtK", metricAverage(rows, EvalCategory.LONG_TERM_MEMORY, "recallAtK"));
    summary.put("overall", overall);
    summary.put("categories", categories);
    summary.put("repeatK", repeatK);
    summary.put("budgetStopped", budgetStopped);
    summary.put("generatedAt", dev.workbench.domain.WorkbenchTime.now());
    return summary;
  }

  private static Map<String, Object> categorySummary(List<CaseRow> rows, int repeatK) {
    var result = new LinkedHashMap<String, Object>();
    int total = rows.size();
    int passed = (int) rows.stream().filter(CaseRow::passed).count();
    double score = rows.isEmpty() ? 0 : rows.stream().mapToDouble(CaseRow::codeScore).average().orElse(0);
    var bySample = new LinkedHashMap<String, List<CaseRow>>();
    for (var row : rows) bySample.computeIfAbsent(row.sampleId(), ignored -> new ArrayList<>()).add(row);
    int stableSamples = 0;
    for (var sampleRows : bySample.values()) {
      boolean allPassed =
          sampleRows.size() >= repeatK && sampleRows.stream().allMatch(CaseRow::passed);
      if (allPassed) stableSamples++;
    }
    result.put("total", total);
    result.put("sampleCount", bySample.size());
    result.put("passed", passed);
    result.put("passRate", ratio(passed, total));
    result.put("score", round(score * 100));
    result.put("passAtOne", ratio(passed, total));
    result.put("passHatK", bySample.isEmpty() ? 0 : ratio(stableSamples, bySample.size()));
    result.put("avgCostUsd", total == 0 ? 0 : round(rows.stream().mapToDouble(CaseRow::costUsd).sum() / total));
    result.put("p95DurationMs", p95(rows));
    result.put("metrics", numericMetricAverages(rows));
    return result;
  }

  private static Map<String, Object> numericMetricAverages(List<CaseRow> rows) {
    var sums = new LinkedHashMap<String, Double>();
    var counts = new LinkedHashMap<String, Integer>();
    for (var row : rows) {
      for (var entry : row.metrics().entrySet()) {
        if (!(entry.getValue() instanceof Number number)) continue;
        sums.merge(entry.getKey(), number.doubleValue(), Double::sum);
        counts.merge(entry.getKey(), 1, Integer::sum);
      }
    }
    var result = new LinkedHashMap<String, Object>();
    // 只保留适合横向比较的核心指标，避免把 Token 总量等绝对量混入雷达解释。
    for (String key :
        List.of(
            "needleRetention",
            "compressionSavedPercent",
            "recallAtK",
            "precisionAtK",
            "mrr",
            "candidateLeakCount",
            "blockRate",
            "gate55CaptureRate",
            "injectionDefenseRate",
            "selectionAccuracy",
            "gatewayPassRate",
            "duplicateRate",
            "validationFirstPassRate",
            "handoffPassRate",
            "fileMatchesExpected")) {
      if (!sums.containsKey(key)) continue;
      result.put(key, round(sums.get(key) / Math.max(1, counts.get(key))));
    }
    return result;
  }

  private static double metricAverage(List<CaseRow> rows, EvalCategory category, String metric) {
    var values =
        rows.stream()
            .filter(row -> row.category() == category)
            .map(row -> row.metrics().get(metric))
            .filter(Number.class::isInstance)
            .map(Number.class::cast)
            .mapToDouble(Number::doubleValue)
            .toArray();
    if (values.length == 0) return 0;
    double sum = 0;
    for (double value : values) sum += value;
    return round(sum / values.length);
  }

  private static long p95(List<CaseRow> rows) {
    if (rows.isEmpty()) return 0;
    var values =
        rows.stream().map(CaseRow::durationMs).sorted(Comparator.naturalOrder()).toList();
    int index = (int) Math.ceil(values.size() * 0.95) - 1;
    return values.get(Math.max(0, Math.min(values.size() - 1, index)));
  }

  private static double ratio(int passed, int total) {
    return total == 0 ? 0 : round((double) passed / total);
  }

  private static double round(double value) {
    return Math.round(value * 10_000.0) / 10_000.0;
  }
}
