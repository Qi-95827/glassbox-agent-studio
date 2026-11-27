package dev.workbench.framework.workflow.plan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从审查正文中确定性提取结构化结论。
 *
 * <p>兼容三类输出：```json 代码块、带 DECISION 标记的纯文本、以及自然语言中的
 * {@code REVISE_REQUIRED} 关键字。解析失败时保守视为 {@code APPROVED}，不会凭猜测触发反思。
 */
public final class ReviewVerdictParser {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Pattern JSON_BLOCK =
      Pattern.compile("```(?:json)?\\s*(\\{.*?\\})\\s*```", Pattern.DOTALL);
  private static final Pattern DECISION_LINE =
      Pattern.compile(
          "(?im)^\\s*(?:DECISION|审查结论|结论)\\s*[:：]\\s*(APPROVED|REVISE_REQUIRED)\\s*$");

  private ReviewVerdictParser() {}

  public static ReviewVerdict parse(String text) {
    if (text == null || text.isBlank())
      return new ReviewVerdict(ReviewVerdict.APPROVED, List.of(), "");
    return parseStructured(text).orElseGet(() -> fallback(text));
  }

  /**
   * 只识别显式结构化裁决：JSON 代码块、`DECISION: ...` 行、或正文中独立的 APPROVED / REVISE_REQUIRED 标记。
   *
   * <p>返回空表示「模型完全没有给出裁决标记」——调用方（反思协调器）必须按保守策略处理，不能默认通过。
   * 保留 {@link #parse(String)} 的宽松默认是为了兼容既有非反思场景与历史单测。
   */
  public static java.util.Optional<ReviewVerdict> parseStructured(String text) {
    if (text == null || text.isBlank()) return java.util.Optional.empty();
    Matcher block = JSON_BLOCK.matcher(text);
    while (block.find()) {
      var parsed = fromJson(block.group(1));
      if (parsed != null) return java.util.Optional.of(parsed);
    }
    Matcher line = DECISION_LINE.matcher(text);
    if (line.find())
      return java.util.Optional.of(
          new ReviewVerdict(
              line.group(1).toUpperCase(Locale.ROOT), List.of(), firstMeaningfulLine(text)));
    String upper = text.toUpperCase(Locale.ROOT);
    if (containsMarker(upper, ReviewVerdict.REVISE_REQUIRED))
      return java.util.Optional.of(
          new ReviewVerdict(ReviewVerdict.REVISE_REQUIRED, List.of(), firstMeaningfulLine(text)));
    // 通过标记必须是独立成行的裁决；"approved by tests" 这类叙述不算正式结论。
    if (containsStandaloneLine(upper, ReviewVerdict.APPROVED))
      return java.util.Optional.of(
          new ReviewVerdict(ReviewVerdict.APPROVED, List.of(), firstMeaningfulLine(text)));
    return java.util.Optional.empty();
  }

  /** 整行只由标记与标点组成（如 `APPROVED` / `APPROVED.`），才算显式通过裁决。 */
  private static boolean containsStandaloneLine(String upper, String marker) {
    for (String line : upper.lines().map(String::strip).toList()) {
      if (!line.startsWith(marker)) continue;
      String rest = line.substring(marker.length()).strip();
      if (rest.isEmpty() || rest.matches("^[:：。.,;；!！)）]+$")) return true;
    }
    return false;
  }

  /** 标记必须作为独立词出现，避免 "approved by tests" 这类叙述被误当成正式裁决。 */
  private static boolean containsMarker(String upper, String marker) {
    int index = upper.indexOf(marker);
    while (index >= 0) {
      boolean leftOk =
          index == 0
              || !Character.isLetterOrDigit(upper.charAt(index - 1))
              || upper.charAt(index - 1) == '_';
      int end = index + marker.length();
      boolean rightOk =
          end >= upper.length()
              || !Character.isLetterOrDigit(upper.charAt(end))
              || upper.charAt(end) == '_';
      if (leftOk && rightOk) return true;
      index = upper.indexOf(marker, index + 1);
    }
    return false;
  }

  /** 宽松兜底：只有显式 REVISE_REQUIRED 才返工，其余正文按历史语义视为通过（不用于反思节点）。 */
  private static ReviewVerdict fallback(String text) {
    String upper = text.toUpperCase(Locale.ROOT);
    if (upper.contains(ReviewVerdict.REVISE_REQUIRED))
      return new ReviewVerdict(ReviewVerdict.REVISE_REQUIRED, List.of(), firstMeaningfulLine(text));
    return new ReviewVerdict(ReviewVerdict.APPROVED, List.of(), firstMeaningfulLine(text));
  }

  private static ReviewVerdict fromJson(String raw) {
    try {
      JsonNode root = JSON.readTree(raw);
      if (root == null || !root.hasNonNull("decision")) return null;
      String decision = root.path("decision").asText();
      if (!ReviewVerdict.APPROVED.equalsIgnoreCase(decision)
          && !ReviewVerdict.REVISE_REQUIRED.equalsIgnoreCase(decision)) return null;
      var defects = new ArrayList<ReviewDefect>();
      for (JsonNode item : root.path("defects")) {
        if (item == null || !item.isObject()) continue;
        if (defects.size() >= 20) break;
        defects.add(
            new ReviewDefect(
                item.path("severity").asText("MINOR"),
                item.path("filePath").asText(null),
                item.path("summary").asText(""),
                item.path("suggestedFix").asText("")));
      }
      return new ReviewVerdict(
          decision.toUpperCase(Locale.ROOT), defects, root.path("summary").asText(""));
    } catch (Exception ignored) {
      return null;
    }
  }

  private static String firstMeaningfulLine(String text) {
    for (String line : text.lines().map(String::strip).toList())
      if (!line.isBlank() && !line.startsWith("```")) return line.length() <= 220 ? line : line.substring(0, 220);
    return "";
  }
}
