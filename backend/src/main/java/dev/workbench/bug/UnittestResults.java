package dev.workbench.bug;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 受信 unittest 命令的逐用例结果。
 *
 * <p>只依据容器实际输出记录完整测试标识、结果和断言；相似名称、未执行或被跳过的用例不会冒充目标用例。
 */
public final class UnittestResults {
  public enum Outcome {
    PASSED,
    FAILED,
    ERROR,
    SKIPPED,
    EXPECTED_FAILURE,
    UNEXPECTED_SUCCESS
  }

  public record Case(String testId, Outcome outcome, String message) {
    public Case {
      if (testId == null || testId.isBlank() || outcome == null)
        throw new IllegalArgumentException("测试用例记录无效。");
      message = message == null ? "" : message;
    }
  }

  public record Report(int discovered, List<Case> cases) {
    public Report {
      if (discovered < 0) throw new IllegalArgumentException("测试数量无效。");
      cases = List.copyOf(cases);
    }

    /** 完整标识精确匹配；不使用前缀、后缀或包含关系。 */
    public Case find(String testId) {
      return cases.stream().filter(test -> test.testId().equals(testId)).findFirst().orElse(null);
    }
  }

  private static final int MESSAGE_LIMIT = 2000;
  private static final Pattern DISCOVERED = Pattern.compile("TESTS_DISCOVERED=(\\d+)");
  private static final Pattern RUN_LINE =
      Pattern.compile(
          "^\\S+ \\(([^)\\s]+)\\)(?:[^\\r\\n]*?)\\.\\.\\. "
              + "(ok|FAIL|ERROR|skipped[^\\r\\n]*|expected failure|unexpected success)\\s*$",
          Pattern.MULTILINE);
  private static final Pattern SUMMARY_HEADER =
      Pattern.compile("^(FAIL|ERROR|SKIPPED): .*\\(([^)\\r\\n]+)\\)\\s*$");
  private static final Pattern BLOCK_SEPARATOR = Pattern.compile("^={10,}\\s*$");
  private static final Pattern HEADER_SEPARATOR = Pattern.compile("^-{10,}\\s*$");
  private static final Pattern EXCEPTION_LINE = Pattern.compile("^[A-Za-z_][A-Za-z0-9_.]*:.*$");

  private UnittestResults() {}

  public static Report parse(String output) {
    if (output == null) return new Report(0, List.of());
    String text = output.replace("\r\n", "\n").replace('\r', '\n');
    int discovered = 0;
    Matcher found = DISCOVERED.matcher(text);
    if (found.find()) {
      try {
        discovered = Integer.parseInt(found.group(1));
      } catch (NumberFormatException ignored) {
        /* 无法解析时按“未发现测试”处理，不能当作通过。 */
      }
    }

    Map<String, Case> cases = new LinkedHashMap<>();
    Matcher run = RUN_LINE.matcher(text);
    while (run.find()) {
      String testId = run.group(1);
      String raw = run.group(2);
      Outcome outcome;
      String message = "";
      switch (raw) {
        case "ok" -> outcome = Outcome.PASSED;
        case "FAIL" -> outcome = Outcome.FAILED;
        case "ERROR" -> outcome = Outcome.ERROR;
        case "expected failure" -> outcome = Outcome.EXPECTED_FAILURE;
        case "unexpected success" -> outcome = Outcome.UNEXPECTED_SUCCESS;
        default -> {
          outcome = Outcome.SKIPPED;
          message = skippedMessage(raw);
        }
      }
      cases.put(testId, new Case(testId, outcome, message));
    }

    // 汇总块包含逐用例断言；同名运行行以汇总块为准，避免只记录 "FAIL" 而丢失断言。
    String[] lines = text.split("\n", -1);
    for (int i = 0; i < lines.length; i++) {
      Matcher header = SUMMARY_HEADER.matcher(lines[i].strip());
      if (!header.matches()) continue;
      Outcome outcome =
          switch (header.group(1)) {
            case "FAIL" -> Outcome.FAILED;
            case "ERROR" -> Outcome.ERROR;
            default -> Outcome.SKIPPED;
          };
      List<String> body = new ArrayList<>();
      boolean bodyStarted = false;
      for (int j = i + 1; j < lines.length; j++) {
        String line = lines[j];
        if (BLOCK_SEPARATOR.matcher(line).matches()) break;
        if (HEADER_SEPARATOR.matcher(line).matches()) {
          if (!bodyStarted) {
            bodyStarted = true;
            continue;
          }
          break;
        }
        if (bodyStarted) body.add(line);
      }
      String testId = header.group(2);
      cases.put(testId, new Case(testId, outcome, extractMessage(outcome, body)));
    }
    return new Report(discovered, new ArrayList<>(cases.values()));
  }

  private static String skippedMessage(String raw) {
    String value = raw.substring("skipped".length()).strip();
    if (value.length() >= 2
        && ((value.startsWith("'") && value.endsWith("'"))
            || (value.startsWith("\"") && value.endsWith("\"")))) {
      value = value.substring(1, value.length() - 1);
    }
    return value;
  }

  /** 断言保留异常行及其后续内容；跳过原因保留原始说明。 */
  private static String extractMessage(Outcome outcome, List<String> body) {
    while (!body.isEmpty() && body.getLast().isBlank()) body.removeLast();
    int start = 0;
    if (outcome != Outcome.SKIPPED) {
      for (int i = body.size() - 1; i >= 0; i--) {
        String line = body.get(i).strip();
        if (EXCEPTION_LINE.matcher(line).matches() && !line.startsWith("Traceback")) {
          start = i;
          break;
        }
      }
    }
    String message = String.join("\n", body.subList(start, body.size())).strip();
    return message.length() > MESSAGE_LIMIT ? message.substring(0, MESSAGE_LIMIT) : message;
  }
}
