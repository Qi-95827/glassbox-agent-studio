package dev.workbench.framework.trace;

import java.util.regex.Pattern;

/** 玻璃盒透视数据脱敏与截断器：在写入持久化层前屏蔽常见密钥并限制单条体积。 */
public final class SensitiveDataMasker {
  public static final int DEFAULT_MAX_CHARS = 65_536;

  private static final Pattern BEARER_PATTERN =
      Pattern.compile("(?i)(Bearer\\s+)[A-Za-z0-9._~+/=-]{8,}");
  private static final Pattern API_KEY_PATTERN =
      Pattern.compile("\\b(sk-[A-Za-z0-9_-]{12,})\\b");
  private static final Pattern SECRET_KV_PATTERN =
      Pattern.compile(
          "(?i)(\"?(?:api[_-]?key|secret|password|passwd|token|authorization|cookie)\"?\\s*[:=]\\s*\"?)([^\"\\s,;\\r\\n]{6,})");

  private SensitiveDataMasker() {}

  public record TruncatedText(String text, boolean truncated) {}

  /** 对常见密钥、Bearer Token、Cookie 和敏感键值对进行掩码。 */
  public static String mask(String input) {
    if (input == null || input.isEmpty()) return input;
    String value = BEARER_PATTERN.matcher(input).replaceAll("$1***MASKED***");
    value = API_KEY_PATTERN.matcher(value).replaceAll("sk-***MASKED***");
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
