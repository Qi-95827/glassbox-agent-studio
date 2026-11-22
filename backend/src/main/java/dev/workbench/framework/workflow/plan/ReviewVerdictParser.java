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
    Matcher block = JSON_BLOCK.matcher(text);
    while (block.find()) {
      var parsed = fromJson(block.group(1));
      if (parsed != null) return parsed;
    }
    Matcher line = DECISION_LINE.matcher(text);
    if (line.find())
      return new ReviewVerdict(
          line.group(1).toUpperCase(Locale.ROOT), List.of(), firstMeaningfulLine(text));
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
