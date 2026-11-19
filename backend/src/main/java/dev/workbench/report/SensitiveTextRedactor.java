package dev.workbench.report;

import java.util.regex.Pattern;

/** 报告、评测数据集与导出共用的敏感文本脱敏规则，允许路径中包含空格。 */
public final class SensitiveTextRedactor {
  private static final Pattern WINDOWS_PATH =
      Pattern.compile("[A-Za-z]:(?:[\\\\/][^\\\\/:*?\"<>|\\r\\n，。；、]*)+");
  private static final Pattern UNIX_PATH =
      Pattern.compile("(?<![\\w:])/(?:[^/\\r\\n\"'<>|，。；、]+/)+[^/\\r\\n\"'<>|，。；、]+");
  private static final Pattern EMAIL =
      Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
  private static final Pattern OPENAI_KEY = Pattern.compile("sk-[A-Za-z0-9_-]{8,}");
  private static final Pattern GITHUB_KEY = Pattern.compile("ghp_[A-Za-z0-9]{8,}");
  private static final Pattern BEARER = Pattern.compile("Bearer\\s+[A-Za-z0-9._-]{8,}");
  private static final Pattern LABELLED_SECRET =
      Pattern.compile("(?i)(api[_-]?key|token|secret|password)\\s*[=:]\\s*[^\\s,;]{4,}");

  private SensitiveTextRedactor() {}

  public static String redact(String text) {
    String value = text == null ? "" : text;
    value = WINDOWS_PATH.matcher(value).replaceAll("[已脱敏路径]");
    value = UNIX_PATH.matcher(value).replaceAll("[已脱敏路径]");
    value = EMAIL.matcher(value).replaceAll("[已脱敏邮箱]");
    value = OPENAI_KEY.matcher(value).replaceAll("[已脱敏密钥]");
    value = GITHUB_KEY.matcher(value).replaceAll("[已脱敏密钥]");
    value = BEARER.matcher(value).replaceAll("Bearer [已脱敏]");
    value = LABELLED_SECRET.matcher(value).replaceAll("$1=[已脱敏凭据]");
    return value;
  }
}
