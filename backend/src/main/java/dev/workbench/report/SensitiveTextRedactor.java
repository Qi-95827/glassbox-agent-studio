package dev.workbench.report;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 报告、评测数据集与导出共用的敏感文本脱敏规则，允许路径中包含空格。
 *
 * <p>顺序是「先删凭据、再删路径与邮箱」：凭据模式不会误吞 URL 协议头；路径规则要求盘符前不是字母数字， 因此 {@code https://host/a/b} 不会被当成 Windows
 * 盘符路径整段吃掉。
 *
 * <p>脱敏标记只包含中文与方括号，不含 HTML 元字符，所以对已转义文本脱敏不会破坏 HTML 结构。
 */
public final class SensitiveTextRedactor {
  /** 盘符前不能紧邻字母数字，避免把 {@code https:} 里的 {@code s:} 误判为 Windows 路径。 */
  private static final Pattern WINDOWS_PATH =
      Pattern.compile("(?<![A-Za-z0-9])[A-Za-z]:(?:[\\\\/][^\\\\/:*?\"<>|\\r\\n，。；、]*)+");

  private static final Pattern UNIX_PATH =
      Pattern.compile("(?<![\\w:/])/(?:[^/\\r\\n\"'<>|，。；、]+/)+[^/\\r\\n\"'<>|，。；、]+");
  private static final Pattern EMAIL =
      Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");

  /** PEM 私钥整段优先于其它模式。 */
  private static final Pattern PEM_PRIVATE_KEY =
      Pattern.compile(
          "-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]*PRIVATE KEY-----");

  private static final Pattern JWT =
      Pattern.compile("eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}");
  private static final Pattern AWS_ACCESS_KEY = Pattern.compile("\\b(?:AKIA|ASIA)[0-9A-Z]{16}\\b");
  private static final Pattern GOOGLE_API_KEY = Pattern.compile("\\bAIza[0-9A-Za-z_-]{35,}");
  private static final Pattern GITHUB_KEY =
      Pattern.compile("\\b(?:gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,})\\b");
  private static final Pattern GITLAB_KEY = Pattern.compile("\\bglpat-[A-Za-z0-9_-]{16,}\\b");
  private static final Pattern OPENAI_KEY = Pattern.compile("\\bsk-[A-Za-z0-9_-]{8,}\\b");
  private static final Pattern BEARER = Pattern.compile("(?i)Bearer\\s+[A-Za-z0-9._-]{8,}");

  /** URL 内嵌凭据：{@code scheme://user:password@host} 只保留协议与主机。 */
  private static final Pattern URL_CREDENTIALS =
      Pattern.compile("(?<=://)[^/\\s:@'\"]+:[^/\\s@'\"]+@");

  /** 键值对允许单引号、双引号、JSON 转义引号与 HTML 转义引号。 */
  private static final Pattern LABELLED_SECRET =
      Pattern.compile(
          "(?i)((?:api[_-]?key|token|secret|password|passwd|credential|authorization|cookie)"
              + "[\"']?\\s*[=:]\\s*[\"']?)([^\"'\\s,;<>]{4,})");

  private SensitiveTextRedactor() {}

  /** 依次套用凭据、URL、路径与邮箱规则。 */
  public static String redact(String text) {
    String value = text == null ? "" : text;
    value = PEM_PRIVATE_KEY.matcher(value).replaceAll("[已脱敏私钥]");
    value = JWT.matcher(value).replaceAll("[已脱敏令牌]");
    value = AWS_ACCESS_KEY.matcher(value).replaceAll("[已脱敏密钥]");
    value = GOOGLE_API_KEY.matcher(value).replaceAll("[已脱敏密钥]");
    value = GITHUB_KEY.matcher(value).replaceAll("[已脱敏密钥]");
    value = GITLAB_KEY.matcher(value).replaceAll("[已脱敏密钥]");
    value = OPENAI_KEY.matcher(value).replaceAll("[已脱敏密钥]");
    value = BEARER.matcher(value).replaceAll("Bearer [已脱敏]");
    value = URL_CREDENTIALS.matcher(value).replaceAll("[已脱敏凭据]@");
    value = LABELLED_SECRET.matcher(value).replaceAll("$1[已脱敏凭据]");
    value = WINDOWS_PATH.matcher(value).replaceAll("[已脱敏路径]");
    value = UNIX_PATH.matcher(value).replaceAll("[已脱敏路径]");
    value = EMAIL.matcher(value).replaceAll("[已脱敏邮箱]");
    return value;
  }

  /** 供测试与调用方确认规则覆盖范围（只读）。 */
  static List<String> ruleNames() {
    return List.of(
        "pem-private-key",
        "jwt",
        "aws-access-key",
        "google-api-key",
        "github-token",
        "gitlab-token",
        "openai-key",
        "bearer",
        "url-credentials",
        "labelled-secret",
        "windows-path",
        "unix-path",
        "email");
  }
}
