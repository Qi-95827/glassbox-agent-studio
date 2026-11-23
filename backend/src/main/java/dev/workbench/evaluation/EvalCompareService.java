package dev.workbench.evaluation;

import dev.workbench.framework.evaluation.EvalCategory;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * A/B 消融对照。
 *
 * <p>只比较两个真实批次的冻结快照与逐题结果，不重新执行、不推断原因。配置差异、六维 Δ、成本/延迟 Δ
 * 与逐题 IMPROVED / REGRESSED / UNCHANGED 均由持久化结果计算。
 */
@Service
public class EvalCompareService {
  private static final List<String> CONFIG_FIELDS =
      List.of(
          "modelMode",
          "modelConnectionId",
          "enableCompaction",
          "enableLongTermMemory",
          "enableReflection",
          "enableMcp",
          "repeatK",
          "sampleLimit",
          "maxCostUsd",
          "judgeModelConnectionId");
  private static final Map<String, String> OVERALL_KEYS = new LinkedHashMap<>();

  static {
    OVERALL_KEYS.put("passRate", "passRate");
    OVERALL_KEYS.put("score", "score");
    OVERALL_KEYS.put("safetyBlockRate", "safetyBlockRate");
    OVERALL_KEYS.put("avgNeedleRetention", "avgNeedleRetention");
    OVERALL_KEYS.put("avgRecallAtK", "avgRecallAtK");
    OVERALL_KEYS.put("avgCostUsd", "avgCostUsd");
    OVERALL_KEYS.put("p95DurationMs", "p95DurationMs");
  }

  private final EvalLabService lab;

  public EvalCompareService(EvalLabService lab) {
    this.lab = lab;
  }

  public Map<String, Object> compare(String baselineId, String candidateId) {
    if (baselineId == null || candidateId == null || baselineId.equals(candidateId))
      throw new IllegalArgumentException("请选择两个不同的评测批次。");
    var baseline = lab.detail(baselineId);
    var candidate = lab.detail(candidateId);
    var baseSummary = summary(baseline);
    var candidateSummary = summary(candidate);
    var baseConfig = baseline.run().config();
    var candidateConfig = candidate.run().config();

    var result = new LinkedHashMap<String, Object>();
    result.put(
        "baseline",
        Map.of(
            "id", baseline.run().id(),
            "status", baseline.run().status(),
            "sourceKind", baseline.run().sourceKind(),
            "sourceKey", baseline.run().sourceKey() == null ? "" : baseline.run().sourceKey(),
            "createdAt", baseline.run().createdAt()));
    result.put(
        "candidate",
        Map.of(
            "id", candidate.run().id(),
            "status", candidate.run().status(),
            "sourceKind", candidate.run().sourceKind(),
            "sourceKey", candidate.run().sourceKey() == null ? "" : candidate.run().sourceKey(),
            "createdAt", candidate.run().createdAt()));
    result.put("comparable", comparable(baseline, candidate));
    result.put("configDiff", configDiff(baseConfig, candidateConfig));
    result.put("categoryComparison", categoryComparison(baseSummary, candidateSummary));
    result.put("metricDeltas", metricDeltas(baseSummary, candidateSummary));
    result.put("caseDiff", caseDiff(baseline, candidate));
    result.put("judge", judgeDiff(baseline, candidate));
    return result;
  }

  private static boolean comparable(EvalLabService.Detail baseline, EvalLabService.Detail candidate) {
    var baseCategories = new LinkedHashSet<>(baseline.run().categories());
    var candidateCategories = new LinkedHashSet<>(candidate.run().categories());
    Object baseRepeat = baseline.run().config().get("repeatK");
    Object candidateRepeat = candidate.run().config().get("repeatK");
    return baseCategories.equals(candidateCategories)
        && java.util.Objects.equals(baseRepeat, candidateRepeat);
  }

  private static List<Map<String, Object>> configDiff(
      Map<String, Object> baseline, Map<String, Object> candidate) {
    var rows = new ArrayList<Map<String, Object>>();
    for (String field : CONFIG_FIELDS) {
      Object left = baseline.get(field);
      Object right = candidate.get(field);
      if (java.util.Objects.equals(left, right)) continue;
      var row = new LinkedHashMap<String, Object>();
      row.put("field", field);
      row.put("baseline", left);
      row.put("candidate", right);
      rows.add(row);
    }
    return List.copyOf(rows);
  }

  private static List<Map<String, Object>> categoryComparison(
      Map<String, Object> baseline, Map<String, Object> candidate) {
    var baseCategories = categories(baseline);
    var candidateCategories = categories(candidate);
    var rows = new ArrayList<Map<String, Object>>();
    for (EvalCategory category : EvalCategory.values()) {
      var left = map(baseCategories.get(category.name()));
      var right = map(candidateCategories.get(category.name()));
      double leftScore = number(left.get("score"));
      double rightScore = number(right.get("score"));
      var row = new LinkedHashMap<String, Object>();
      row.put("category", category.name());
      row.put("baselineScore", leftScore);
      row.put("candidateScore", rightScore);
      row.put("delta", round(rightScore - leftScore));
      row.put("baselinePassRate", number(left.get("passRate")));
      row.put("candidatePassRate", number(right.get("passRate")));
      row.put("baselineSampleCount", number(left.get("total")));
      row.put("candidateSampleCount", number(right.get("total")));
      rows.add(Map.copyOf(row));
    }
    return List.copyOf(rows);
  }

  private static List<Map<String, Object>> metricDeltas(
      Map<String, Object> baseline, Map<String, Object> candidate) {
    var left = map(baseline.get("overall"));
    var right = map(candidate.get("overall"));
    var rows = new ArrayList<Map<String, Object>>();
    for (var entry : OVERALL_KEYS.entrySet()) {
      double baseValue = number(left.get(entry.getValue()));
      double candidateValue = number(right.get(entry.getValue()));
      var row = new LinkedHashMap<String, Object>();
      row.put("metric", entry.getKey());
      row.put("baseline", baseValue);
      row.put("candidate", candidateValue);
      row.put("delta", round(candidateValue - baseValue));
      rows.add(Map.copyOf(row));
    }
    return List.copyOf(rows);
  }

  private static List<Map<String, Object>> caseDiff(
      EvalLabService.Detail baseline, EvalLabService.Detail candidate) {
    var left = caseScores(baseline.cases());
    var right = caseScores(candidate.cases());
    var keys = new LinkedHashSet<String>();
    keys.addAll(left.keySet());
    keys.addAll(right.keySet());
    var rows = new ArrayList<Map<String, Object>>();
    for (String key : keys) {
      var baseCase = left.get(key);
      var candidateCase = right.get(key);
      double baseScore = baseCase == null ? 0 : baseCase.score();
      double candidateScore = candidateCase == null ? 0 : candidateCase.score();
      String status;
      if (baseCase == null || candidateCase == null) status = "UNCHANGED";
      else if (candidateScore - baseScore > 0.0001) status = "IMPROVED";
      else if (candidateScore - baseScore < -0.0001) status = "REGRESSED";
      else status = "UNCHANGED";
      var row = new LinkedHashMap<String, Object>();
      row.put("key", key);
      row.put("category", key.substring(0, key.indexOf('|')));
      row.put("sampleId", key.substring(key.indexOf('|') + 1));
      row.put("titleZh", valueOrEmpty(candidateCase == null ? baseCase.titleZh() : candidateCase.titleZh()));
      row.put("titleEn", valueOrEmpty(candidateCase == null ? baseCase.titleEn() : candidateCase.titleEn()));
      row.put("baselineScore", round(baseScore * 100));
      row.put("candidateScore", round(candidateScore * 100));
      row.put("baselinePassed", baseCase != null && baseCase.passed());
      row.put("candidatePassed", candidateCase != null && candidateCase.passed());
      row.put("status", status);
      rows.add(Map.copyOf(row));
    }
    return List.copyOf(rows);
  }

  private static Map<String, Object> judgeDiff(
      EvalLabService.Detail baseline, EvalLabService.Detail candidate) {
    double baseJudge = judgeAverage(baseline.run().summary());
    double candidateJudge = judgeAverage(candidate.run().summary());
    return Map.of(
        "baselineCostUsd", round(baseJudge),
        "candidateCostUsd", round(candidateJudge),
        "enabled", candidate.run().config().get("judgeModelConnectionId") != null);
  }

  private static String valueOrEmpty(String value) {
    return value == null ? "" : value;
  }

  private record CaseScore(double score, boolean passed, String titleZh, String titleEn) {}

  private static Map<String, CaseScore> caseScores(List<EvalLabService.CaseView> cases) {
    var grouped = new LinkedHashMap<String, List<EvalLabService.CaseView>>();
    for (var item : cases)
      grouped
          .computeIfAbsent(item.category() + "|" + item.sampleId(), ignored -> new ArrayList<>())
          .add(item);
    var result = new LinkedHashMap<String, CaseScore>();
    for (var entry : grouped.entrySet()) {
      double score =
          entry.getValue().stream().mapToDouble(EvalLabService.CaseView::codeScore).average().orElse(0);
      boolean passed =
          entry.getValue().stream().filter(EvalLabService.CaseView::passed).count()
              >= Math.ceil(entry.getValue().size() / 2.0);
      var first = entry.getValue().getFirst();
      result.put(entry.getKey(), new CaseScore(score, passed, first.titleZh(), first.titleEn()));
    }
    return result;
  }

  private static double judgeAverage(Map<String, Object> summary) {
    if (summary == null) return 0;
    return number(summary.get("judgeCostUsd"));
  }

  private static Map<String, Object> summary(EvalLabService.Detail detail) {
    return detail.run().summary() == null ? Map.of() : detail.run().summary();
  }

  private static Map<String, Object> categories(Map<String, Object> summary) {
    return map(summary.get("categories"));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Object value) {
    return value instanceof Map<?, ?> source ? (Map<String, Object>) source : Map.of();
  }

  private static double number(Object value) {
    if (value instanceof Number number) return number.doubleValue();
    try {
      return value == null ? 0 : Double.parseDouble(String.valueOf(value));
    } catch (RuntimeException e) {
      return 0;
    }
  }

  private static double round(double value) {
    return Math.round(value * 10_000.0) / 10_000.0;
  }
}
