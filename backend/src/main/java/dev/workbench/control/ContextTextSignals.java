package dev.workbench.control;

import java.util.regex.Pattern;

/** 上下文结构信号匹配与单行截断：路径、异常、数字与强约束共同决定正文价值。 */
final class ContextTextSignals {
  private static final Pattern PATH_PATTERN =
      Pattern.compile(
          "([A-Za-z0-9_./-]+\\.(?:java|js|jsx|ts|tsx|py|html|css|md|json|sql|yml|yaml|xml|sh))");
  private static final Pattern EXCEPTION_PATTERN =
      Pattern.compile("(?i)(exception|error|failed|failure|caused by|报错|异常|失败|超时|traceback)");
  private static final Pattern NUMBER_PATTERN = Pattern.compile("\\d{2,}");

  private ContextTextSignals() {}

  /** 正文是否提到受支持的文件路径。 */
  static boolean hasPath(String text) {
    return text != null && PATH_PATTERN.matcher(text).find();
  }

  /** 正文是否提到异常、失败或超时。 */
  static boolean hasException(String text) {
    return text != null && EXCEPTION_PATTERN.matcher(text).find();
  }

  /** 正文是否包含两位以上的数字（版本、行号、耗时等硬事实）。 */
  static boolean hasNumber(String text) {
    return text != null && NUMBER_PATTERN.matcher(text).find();
  }

  /** 结构加权分：路径、异常、数字、待办与强约束依次提升正文价值，上限为 1。 */
  static double structuralBoost(String content) {
    if (content == null || content.isBlank()) return 0;
    double score = 0;
    if (PATH_PATTERN.matcher(content).find()) score += 0.35;
    if (EXCEPTION_PATTERN.matcher(content).find()) score += 0.45;
    if (NUMBER_PATTERN.matcher(content).find()) score += 0.15;
    if (content.contains("TODO") || content.contains("待办") || content.contains("未解决")) score += 0.2;
    if (content.contains("禁止") || content.contains("不得") || content.contains("必须")) score += 0.2;
    return Math.min(1, score);
  }

  /** 压成单行并按上限截断，超长时以省略号收尾。 */
  static String compact(String value, int max) {
    if (value == null) return "";
    String oneLine = value.replace('\n', ' ').strip();
    return oneLine.length() <= max ? oneLine : oneLine.substring(0, max - 1) + "…";
  }
}
