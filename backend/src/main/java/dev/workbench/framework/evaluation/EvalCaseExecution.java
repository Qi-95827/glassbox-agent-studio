package dev.workbench.framework.evaluation;

import java.util.List;
import java.util.Map;

/**
 * 单题真实执行轨迹与指标。
 *
 * <p>{@code evidence} 是确定性代码裁判的输入，必须来自实际组件返回值；{@code trace} 是玻璃盒穿透抽屉的
 * 结构化明细；{@code assertions} 由代码裁判填写，执行器不得伪造 PASS。
 */
public record EvalCaseExecution(
    Map<String, Object> evidence,
    Map<String, Object> metrics,
    Map<String, Object> trace,
    String linkedRunId,
    String rawOutput,
    long durationMs,
    int inputTokens,
    int outputTokens,
    double costUsd) {
  public EvalCaseExecution {
    evidence = immutable(evidence);
    metrics = immutable(metrics);
    trace = immutable(trace);
    rawOutput = rawOutput == null ? "" : rawOutput;
    durationMs = Math.max(0, durationMs);
    inputTokens = Math.max(0, inputTokens);
    outputTokens = Math.max(0, outputTokens);
    costUsd = Math.max(0, costUsd);
  }

  /** 执行轨迹允许缺少可选字段，但不可让 null 在聚合阶段引发 NPE。 */
  private static Map<String, Object> immutable(Map<String, Object> source) {
    if (source == null || source.isEmpty()) return Map.of();
    var copy = new java.util.LinkedHashMap<String, Object>();
    for (var entry : source.entrySet())
      if (entry.getKey() != null && entry.getValue() != null)
        copy.put(entry.getKey(), entry.getValue());
    return Map.copyOf(copy);
  }

  /** 执行器自身无法完成时使用；该结果会以 false 进入汇总，并保留真实错误原因。 */
  public static EvalCaseExecution failed(String reason, Map<String, Object> trace) {
    return new EvalCaseExecution(
        Map.of("executionError", reason == null ? "unknown" : reason),
        Map.of("executionFailed", true),
        trace == null ? Map.of("error", reason == null ? "unknown" : reason) : trace,
        null,
        "",
        0,
        0,
        0,
        0);
  }

  public EvalCaseExecution withAssertions(List<Map<String, Object>> graded, double score) {
    var merged = new java.util.LinkedHashMap<String, Object>(metrics);
    merged.put("codeScore", score);
    merged.put("assertionCount", graded == null ? 0 : graded.size());
    return new EvalCaseExecution(
        evidence, merged, trace, linkedRunId, rawOutput, durationMs, inputTokens, outputTokens, costUsd);
  }
}
