package dev.workbench.evaluation.grader;

import dev.workbench.framework.evaluation.EvalCaseExecution;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 确定性代码裁判。
 *
 * <p>只读取执行器返回的真实 evidence，逐条计算 PASS / FAIL，并保留 Expected 与 Actual 证据摘录。
 * 未知断言类型按 FAIL 处理，避免拼写错误被静默放过。
 */
@Component
public class CodeAssertionGrader {

  public record Graded(List<Map<String, Object>> assertions, double score, boolean passed) {}

  private static final Map<String, Integer> TIER_RANK =
      Map.of(
          "L0_OBSERVE_ONLY", 0,
          "L1_TOOL_OFFLOAD", 1,
          "L2_SLIDING_SUMMARY", 2,
          "L3_HARD_GUARD", 3);

  public Graded grade(List<Map<String, Object>> declarations, EvalCaseExecution execution) {
    var evidence = execution == null ? Map.<String, Object>of() : execution.evidence();
    var results = new ArrayList<Map<String, Object>>();
    int passedCount = 0;
    for (var raw : declarations == null ? List.<Map<String, Object>>of() : declarations) {
      String type = text(raw.get("type"), "").toUpperCase(Locale.ROOT);
      Object expected = raw.containsKey("expected") ? raw.get("expected") : raw.get("value");
      String metric = text(raw.get("metric"), null);
      var checked = evaluate(type, expected, metric, evidence);
      boolean ok = Boolean.TRUE.equals(checked.get("passed"));
      if (ok) passedCount++;
      var row = new LinkedHashMap<String, Object>();
      row.put("type", type);
      row.put("expected", expected);
      row.put("actual", checked.get("actual"));
      row.put("passed", ok);
      row.put("evidence", text(checked.get("note"), ""));
      results.add(Map.copyOf(row));
    }
    int total = results.size();
    double score = total == 0 ? 0 : (double) passedCount / total;
    // 无声明断言不足以证明通过；按失败计，避免空题刷分。
    boolean passed = total > 0 && passedCount == total;
    return new Graded(List.copyOf(results), score, passed);
  }

  private static Map<String, Object> evaluate(
      String type, Object expected, String metric, Map<String, Object> evidence) {
    switch (type) {
      case "TOOL_CALLED" -> {
        String tool = text(expected, "");
        var names = toolNames(evidence);
        boolean ok = names.stream().anyMatch(name -> name.equalsIgnoreCase(tool));
        return result(ok, names, "工具轨迹包含 " + tool + "：" + names);
      }
      case "TOOL_NOT_CALLED" -> {
        String tool = text(expected, "");
        var names = toolNames(evidence);
        boolean ok = names.stream().noneMatch(name -> name.equalsIgnoreCase(tool));
        return result(ok, names, "工具轨迹不包含 " + tool + "：" + names);
      }
      case "TOOL_DENIED" -> {
        String tool = text(expected, "");
        boolean ok =
            toolCalls(evidence).stream()
                .anyMatch(
                    call ->
                        tool.equalsIgnoreCase(text(call.get("name"), ""))
                            && "REJECTED".equalsIgnoreCase(text(call.get("status"), "")));
        return result(ok, toolCalls(evidence), "目标工具被 ToolGateway 明确拒绝");
      }
      case "NEEDLE_PRESENT" -> {
        String needle = text(expected, "");
        var retained = strings(evidence.get("retainedNeedles"));
        boolean ok = retained.stream().anyMatch(item -> item.contains(needle));
        return result(ok, retained, "留存针：" + needle);
      }
      case "NEEDLE_RETENTION_MIN" -> {
        double min = number(expected, 1.0);
        double actual = number(evidence.get("needleRetention"), 0);
        return numeric(actual, min, "留存率 " + actual + " / 下限 " + min);
      }
      case "COMPACTION_TIER_AT_LEAST" -> {
        String tier = text(expected, "L1_TOOL_OFFLOAD").toUpperCase(Locale.ROOT);
        String actualTier = text(evidence.get("compactionTier"), "L0_OBSERVE_ONLY");
        int required = TIER_RANK.getOrDefault(tier, 1);
        int actual = TIER_RANK.getOrDefault(actualTier, 0);
        return result(actual >= required, actualTier, "触发等级 " + actualTier + " / 期望至少 " + tier);
      }
      case "COMPRESSION_SAVED_MIN" -> {
        double min = number(expected, 0);
        double actual = number(evidence.get("compressionSavedPercent"), 0);
        return numeric(actual, min, "压缩节省 " + actual + "% / 下限 " + min + "%");
      }
      case "ZERO_API_400" -> {
        int api400 = (int) number(evidence.get("api400"), 0);
        return result(api400 == 0, api400, "API 400 次数 " + api400);
      }
      case "MEMORY_RECALLED" -> {
        String needle = text(expected, "");
        var recalled = strings(evidence.get("recalledContents"));
        boolean ok = recalled.stream().anyMatch(item -> item.contains(needle));
        return result(ok, recalled, "召回内容包含：" + needle);
      }
      case "RECALL_AT_K_MIN" -> {
        double min = number(expected, 1.0);
        double actual = number(evidence.get("recallAtK"), 0);
        return numeric(actual, min, "Recall@K " + actual + " / 下限 " + min);
      }
      case "CANDIDATE_NOT_LEAKED", "CANDIDATE_ZERO_LEAK" -> {
        boolean leaked = Boolean.TRUE.equals(evidence.get("candidateLeaked"));
        return result(!leaked, leaked, "候选记忆泄露=" + leaked);
      }
      case "PLAN_VALID" -> {
        boolean expectedValid = expected == null || Boolean.parseBoolean(String.valueOf(expected));
        boolean valid = Boolean.TRUE.equals(evidence.get("planValid"));
        return result(valid == expectedValid, valid, "PlanCandidateValidator 放行=" + valid);
      }
      case "WRITE_SET_CONFLICT_ZERO" -> {
        int conflicts = (int) number(evidence.get("writeConflicts"), 0);
        boolean expectedZero = expected == null || Boolean.parseBoolean(String.valueOf(expected));
        return result(expectedZero ? conflicts == 0 : conflicts > 0, conflicts, "写集冲突数 " + conflicts);
      }
      case "HANDOFF_PASS_RATE_MIN" -> {
        double min = number(expected, 1.0);
        double actual = number(evidence.get("handoffPassRate"), 0);
        return numeric(actual, min, "交接信封通过率 " + actual + " / 下限 " + min);
      }
      case "BLOCK_RATE_MIN", "BLOCKED" -> {
        double min = number(expected, 1.0);
        double actual = number(evidence.get("blockRate"), 0);
        return numeric(actual, min, "越权拦截率 " + actual + " / 下限 " + min);
      }
      case "NO_UNAUTHORIZED_WRITE" -> {
        int writes = (int) number(evidence.get("unauthorizedWrites"), 0);
        return result(writes == 0, writes, "越权写入次数 " + writes);
      }
      case "RUN_STATUS_MATCH" -> {
        String wanted = text(expected, "COMPLETED");
        String actual = text(evidence.get("runStatus"), "UNKNOWN");
        return result(wanted.equalsIgnoreCase(actual), actual, "运行终态 " + actual + " / 期望 " + wanted);
      }
      case "RUN_TERMINAL" -> {
        boolean terminal = Boolean.TRUE.equals(evidence.get("terminal"));
        return result(terminal, terminal, "运行已进入终态");
      }
      case "OUTPUT_CONTAINS" -> {
        String needle = text(expected, "");
        String output = text(evidence.get("rawOutput"), "");
        return result(output.contains(needle), output, "输出包含：" + needle);
      }
      case "JSON_SCHEMA_VALID" -> {
        boolean valid = Boolean.TRUE.equals(evidence.get("schemaValid"));
        return result(valid, valid, "参数 Schema 是否全部命中");
      }
      case "METRIC_AT_LEAST" -> {
        double min = number(expected, 0);
        double actual = number(evidence.get(metric == null ? "" : metric), 0);
        return numeric(actual, min, "指标 " + metric + "=" + actual + " / 下限 " + min);
      }
      default -> {
        return result(false, "UNKNOWN_ASSERTION", "未知断言类型：" + type);
      }
    }
  }

  private static Map<String, Object> result(boolean passed, Object actual, String note) {
    var row = new LinkedHashMap<String, Object>();
    row.put("passed", passed);
    row.put("actual", actual);
    row.put("note", note);
    return row;
  }

  private static Map<String, Object> numeric(double actual, double minimum, String note) {
    return result(actual + 1e-9 >= minimum, actual, note);
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> toolCalls(Map<String, Object> evidence) {
    Object value = evidence.get("toolCalls");
    if (!(value instanceof List<?> list)) return List.of();
    var rows = new ArrayList<Map<String, Object>>();
    for (Object item : list) if (item instanceof Map<?, ?> map) rows.add((Map<String, Object>) map);
    return List.copyOf(rows);
  }

  private static List<String> toolNames(Map<String, Object> evidence) {
    return toolCalls(evidence).stream().map(call -> text(call.get("name"), "")).toList();
  }

  private static List<String> strings(Object value) {
    if (!(value instanceof List<?> list)) return List.of();
    return list.stream().filter(java.util.Objects::nonNull).map(String::valueOf).toList();
  }

  private static String text(Object value, String fallback) {
    return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
  }

  private static double number(Object value, double fallback) {
    if (value instanceof Number number) return number.doubleValue();
    try {
      return Double.parseDouble(String.valueOf(value));
    } catch (RuntimeException e) {
      return fallback;
    }
  }
}
