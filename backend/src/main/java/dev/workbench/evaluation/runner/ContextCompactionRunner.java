package dev.workbench.evaluation.runner;

import dev.workbench.control.MultiTierContextCompactor;
import dev.workbench.framework.context.CompactionRequest;
import dev.workbench.framework.context.ContextMessage;
import dev.workbench.framework.evaluation.EvalCaseExecution;
import dev.workbench.framework.evaluation.EvalCategory;
import dev.workbench.framework.evaluation.EvalCategoryRunner;
import dev.workbench.framework.evaluation.EvalExperimentConfig;
import dev.workbench.framework.evaluation.EvalSample;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 类别 3：四级上下文压缩 NIAH。
 *
 * <p>真实调用 {@link MultiTierContextCompactor}：先构造锚点、真实体量工具报文与埋针，再按目标水位设置
 * 上下文窗口，观察实际触发的压缩等级、留存针与节省比例。留存率直接扫描压缩后的消息列表，不依赖记忆或推测。
 */
@Component
public class ContextCompactionRunner implements EvalCategoryRunner {
  private final MultiTierContextCompactor compactor;

  public ContextCompactionRunner(MultiTierContextCompactor compactor) {
    this.compactor = compactor;
  }

  @Override
  public EvalCategory category() {
    return EvalCategory.CONTEXT_COMPACTION;
  }

  @Override
  public EvalCaseExecution run(
      EvalSample sample, EvalExperimentConfig config, String ownerScopeKey) {
    long started = System.nanoTime();
    var setup = sample.setup();
    double target = number(setup.get("targetUtilization"), 0.65);
    int rounds = Math.max(4, Math.min(30, (int) number(setup.get("haystackRounds"), 8)));
    int blobChars = Math.max(600, Math.min(20_000, (int) number(setup.get("toolBlobChars"), 2_000)));
    var needles = strings(setup.get("needles"));
    String placement = text(setup.get("needlePlacement"), "checkpoint");

    var messages = conversation(sample.prompt(), rounds, blobChars, needles, placement);
    int before = estimate(messages);
    int limit = Math.max(200, (int) Math.round(before / Math.max(0.3, Math.min(0.95, target))));

    var request =
        new CompactionRequest(messages, limit, 4, 2, 0.60, 0.78, 0.92);
    var result = compactor.evaluateAndCompact(request);

    var compactedText = new StringBuilder();
    for (var message : result.messages()) compactedText.append(message.content()).append('\n');
    var retained = new ArrayList<String>();
    for (String needle : needles)
      if (compactedText.toString().contains(needle)) retained.add(needle);
    double retention = needles.isEmpty() ? 1.0 : (double) retained.size() / needles.size();
    int after = estimate(result.messages());
    double savedPercent =
        before <= 0 ? 0 : Math.max(0, (before - after) * 100.0 / before);

    // 上下文超限是真实模型返回 400 的主要结构性原因；工具结果缺少 callId 会导致成对关系被破坏。
    boolean orphanTool = result.messages().stream()
        .anyMatch(message -> "TOOL".equals(message.role()) && message.toolCallId() == null);
    int api400 = (after > limit || orphanTool) ? 1 : 0;

    var metrics = new LinkedHashMap<String, Object>();
    metrics.put("beforeTokens", before);
    metrics.put("afterTokens", after);
    metrics.put("contextLimit", limit);
    metrics.put("needleCount", needles.size());
    metrics.put("retainedCount", retained.size());
    metrics.put("needleRetention", round(retention));
    metrics.put("compressionSavedPercent", round(savedPercent));
    metrics.put("compactionTier", result.breakdown().appliedTier().name());
    metrics.put("offloadedTools", result.breakdown().offloadedToolCallIds().size());
    metrics.put("api400", api400);

    var evidence = new LinkedHashMap<String, Object>(metrics);
    evidence.put("retainedNeedles", retained);
    evidence.put("rawOutput", retained.isEmpty() ? "" : String.join("\n", retained));

    var trace = new LinkedHashMap<String, Object>();
    trace.put("category", category().name());
    trace.put("placement", placement);
    trace.put("rounds", rounds);
    trace.put("toolBlobChars", blobChars);
    trace.put("needles", needles);
    trace.put("retainedNeedles", retained);
    trace.put("segmentTokens", result.breakdown().segmentTokens());
    trace.put("summaryPreview", result.breakdown().summaryPreview());
    trace.put("measurement", "compacted_context_scan");

    long duration = (System.nanoTime() - started) / 1_000_000;
    return new EvalCaseExecution(
        evidence,
        metrics,
        trace,
        null,
        retained.isEmpty() ? "" : String.join("\n", retained),
        duration,
        before,
        after,
        0);
  }

  private static List<ContextMessage> conversation(
      String prompt, int rounds, int blobChars, List<String> needles, String placement) {
    var messages = new ArrayList<ContextMessage>();
    messages.add(
        new ContextMessage(
            "SYSTEM", null, "系统规则：不得扩大授权范围；最终回答必须保留已确认约束。", null));
    messages.add(new ContextMessage("USER", null, "任务目标：" + prompt, null));
    for (int index = 0; index < rounds; index++) {
      var assistant = new StringBuilder("round " + index + " decision: continue controlled inspection");
      if ("checkpoint".equalsIgnoreCase(placement) && index == Math.max(1, rounds / 2)) {
        for (String needle : needles) assistant.append('\n').append(needle);
      }
      messages.add(new ContextMessage("ASSISTANT", null, assistant.toString(), null));
      String content = toolBlob(index, blobChars, needles, placement);
      messages.add(
          new ContextMessage(
              "TOOL", "read_authorized_file", content, "eval-tool-" + index));
    }
    messages.add(
        new ContextMessage(
            "USER",
            null,
            "回查：请仅依据当前上下文复述此前确认的全部关键约束与禁止文件。",
            null));
    return messages;
  }

  private static String toolBlob(
      int index, int chars, List<String> needles, String placement) {
    var builder = new StringBuilder("file=src/module").append(index).append(".js\n");
    int targetNeedle = Math.max(1, 4);
    while (builder.length() < chars) {
      builder.append("line ").append(builder.length()).append(": inspected path src/module")
          .append(index).append(".js without mutation;\n");
      if ("tool_middle".equalsIgnoreCase(placement)
          && index == targetNeedle
          && builder.length() > chars / 2
          && !needles.isEmpty()) {
        for (String needle : needles) builder.append(needle).append('\n');
      }
    }
    return builder.substring(0, chars);
  }

  /** 与 MultiTierContextCompactor 同口径的保守 Token 估算，用于精确设置触发水位。 */
  static int estimate(List<ContextMessage> messages) {
    long total = 0;
    for (var message : messages) {
      int chars = message.content() == null ? 0 : message.content().length();
      total += Math.max(1, (chars + 3L) / 4L);
    }
    return (int) Math.min(Integer.MAX_VALUE, Math.max(0, total));
  }

  private static List<String> strings(Object value) {
    if (!(value instanceof List<?> list)) return List.of();
    return list.stream()
        .filter(java.util.Objects::nonNull)
        .map(String::valueOf)
        .filter(text -> !text.isBlank())
        .toList();
  }

  private static double number(Object value, double fallback) {
    if (value instanceof Number number) return number.doubleValue();
    try {
      return Double.parseDouble(String.valueOf(value));
    } catch (RuntimeException e) {
      return fallback;
    }
  }

  private static String text(Object value, String fallback) {
    return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
  }

  private static double round(double value) {
    return Math.round(value * 10_000.0) / 10_000.0;
  }
}
