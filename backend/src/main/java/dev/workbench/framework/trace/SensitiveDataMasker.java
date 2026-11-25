package dev.workbench.framework.trace;

import java.util.regex.Pattern;

/**
 * 玻璃盒透视数据脱敏与截断器：在写入持久化层前屏蔽常见密钥并限制单条体积。
 *
 * <p>与报告脱敏保持同一套凭据覆盖（PEM/JWT/AWS/Google/GitHub/GitLab/URL 内嵌凭据/单引号键值）， 且所有匹配都不会吞掉 JSON 结构符 {@code }}
 * / {@code ]}，避免脱敏后无法反序列化。
 */
public final class SensitiveDataMasker {
  public static final int DEFAULT_MAX_CHARS = 65_536;

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
  private static final Pattern BEARER_PATTERN =
      Pattern.compile("(?i)(Bearer\\s+)[A-Za-z0-9._~+/=-]{8,}");
  private static final Pattern API_KEY_PATTERN = Pattern.compile("\\bsk-[A-Za-z0-9_-]{12,}\\b");
  private static final Pattern URL_CREDENTIALS =
      Pattern.compile("(?<=://)[^/\\s:@'\"]+:[^/\\s@'\"]+@");

  /** 键值对允许单/双引号与 JSON、HTML 转义引号；值遇到引号、括号、分隔符即停止，绝不吞结构符。 */
  private static final Pattern SECRET_KV_PATTERN =
      Pattern.compile(
          "(?i)(\"?'?(?:api[_-]?key|secret|password|passwd|token|authorization|cookie)'?\"?\\s*[:=]\\s*\"?'?)"
              + "([^\"'\\s,;}\\]]{6,})");

  private SensitiveDataMasker() {}

  public record TruncatedText(String text, boolean truncated) {}

  /** 对常见密钥、Bearer Token、Cookie、URL 凭据和敏感键值对进行掩码。 */
  public static String mask(String input) {
    if (input == null || input.isEmpty()) return input;
    String value = PEM_PRIVATE_KEY.matcher(input).replaceAll("***MASKED-PRIVATE-KEY***");
    value = JWT.matcher(value).replaceAll("***MASKED-TOKEN***");
    value = AWS_ACCESS_KEY.matcher(value).replaceAll("***MASKED-KEY***");
    value = GOOGLE_API_KEY.matcher(value).replaceAll("***MASKED-KEY***");
    value = GITHUB_KEY.matcher(value).replaceAll("***MASKED-KEY***");
    value = GITLAB_KEY.matcher(value).replaceAll("***MASKED-KEY***");
    value = BEARER_PATTERN.matcher(value).replaceAll("$1***MASKED***");
    value = API_KEY_PATTERN.matcher(value).replaceAll("sk-***MASKED***");
    value = URL_CREDENTIALS.matcher(value).replaceAll("***MASKED***@");
    value = SECRET_KV_PATTERN.matcher(value).replaceAll("$1***MASKED***");
    return value;
  }

  /** 先脱敏再按字符上限截断，避免单次大文件或长上下文拖垮存储与前端渲染。 */
  public static TruncatedText maskAndTruncate(String input, int maxChars) {
    if (input == null) return new TruncatedText(null, false);
    String masked = mask(input);
    int limit = Math.max(256, maxChars);
    if (masked.length() <= limit) return new TruncatedText(masked, false);
    return new TruncatedText(masked.substring(0, limit) + "\n...[已截断]", true);
  }

  public static TruncatedText maskAndTruncate(String input) {
    return maskAndTruncate(input, DEFAULT_MAX_CHARS);
  }
}
