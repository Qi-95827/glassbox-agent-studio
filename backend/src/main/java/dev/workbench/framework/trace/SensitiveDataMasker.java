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

  private static final String KV_KEY =
      "(?:api[_-]?key|secret|password|passwd|token|authorization|cookie)";
  private static final String KV_VALUE_CHARS = "[^\"'\\s,;}\\]。，；！？、）》」』】]";

  /**
   * 流式切点检测：与完整脱敏共享同一形态集合（PEM/JWT/AWS/Google/GitHub/GitLab/Bearer/sk-/URL/KV），
   * 并使用与整段规则等价的跨行空白（{@code \s}）与引号形态，保证单行/多行格式化凭据在任意跨帧切点都不会先放走前缀。
   */
  static final Pattern STREAM_PARTIAL =
      Pattern.compile(
          "(?s)sk-[A-Za-z0-9_-]*"
              + "|(?i:bearer)\\s+[A-Za-z0-9._~+/=-]*"
              + "|eyJ[A-Za-z0-9_-]*(?:\\.[A-Za-z0-9_-]*){0,2}"
              + "|(?i:\"?'?"
              + KV_KEY
              + "'?\"?)(?:\\s*[:=]\\s*\"?'?"
              + KV_VALUE_CHARS
              + "*|\\s+)"
              + "|-----BEGIN [A-Z ]*PRIVATE KEY-----(?:[\\s\\S]*?-----END [A-Z ]*PRIVATE KEY-----|[\\s\\S]*)"
              + "|(?<![A-Za-z0-9])(?:AKIA|ASIA)[0-9A-Z]*"
              + "|(?<![A-Za-z0-9])AIza[A-Za-z0-9_-]*"
              + "|(?<![A-Za-z0-9])(?:gh[pousr]_[A-Za-z0-9_]*|github_pat_[A-Za-z0-9_]*)"
              + "|(?<![A-Za-z0-9])glpat-[A-Za-z0-9_-]*"
              + "|://[^/\\s:@'\"]+(?::[^/\\s@'\"]*@?)?");

  private static final Pattern UNCLOSED_PEM =
      Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*");
  /** 仅锚定流末尾的截断令牌；只对 Bearer 忽略大小写并支持跨行空白，绝不误伤普通英文单词（如 Asia），也不重复叠加已脱敏标记。 */
  private static final Pattern TRUNCATED_TOKEN =
      Pattern.compile(
          "(?<![A-Za-z0-9])((?:sk-|github_pat_|gh[pousr]_|glpat-|AIza|AKIA|ASIA|(?i:bearer)\\s+|eyJ))"
              + "(?!\\*\\*\\*MASKED)[A-Za-z0-9._~+/=-]*$");
  /** 仅锚定流末尾尚未闭合的凭据键值（与整段规则一致支持跨行空白与引号），仅在流异常中断时兜底。 */
  private static final Pattern TRUNCATED_KV =
      Pattern.compile(
          "(?i)(\"?'?" + KV_KEY + "'?\"?\\s*[:=]\\s*\"?'?)(?!\\*\\*\\*MASKED)"
              + KV_VALUE_CHARS
              + "+$");
  /** 流末尾截断的 URL 凭据（缺 {@code @}）；排除 1~5 位纯数字端口（如 {@code http://localhost:8088}），避免误伤普通地址。 */
  private static final Pattern TRUNCATED_URL_CREDENTIALS =
      Pattern.compile("://[^/\\s:@'\"]+:(?!\\d{1,5}$)[^/\\s@'\"]+$");

  /** 键值对允许单/双引号与 JSON、HTML 转义引号；值遇到引号、括号、分隔符即停止，绝不吞结构符。 */
  private static final Pattern SECRET_KV_PATTERN =
      Pattern.compile(
          "(?i)(\"?'?(?:api[_-]?key|secret|password|passwd|token|authorization|cookie)'?\"?\\s*[:=]\\s*\"?'?)"
              + "([^\"'\\s,;}\\]。，；！？、）》」』】]{6,})");

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

  /**
   * 正常结束收尾：执行与整段等价的完整脱敏，并对未闭合私钥块兜底；不触发异常截断规则，避免误伤末尾的普通
   * {@code http://localhost:8088} 或 {@code token: 100}。
   */
  public static String maskCompleted(String input) {
    if (input == null || input.isEmpty()) return input;
    String value = mask(input);
    return UNCLOSED_PEM.matcher(value).replaceAll("***MASKED-PRIVATE-KEY***");
  }

  /**
   * 失败/中断收尾：先做完整匹配脱敏，再兜底掩码残留在末尾的未闭合敏感块。
   *
   * <p>流被取消时可能只收到 `-----BEGIN ... PRIVATE KEY-----` 或半截 `"api_key": "sk-...`；
   * 这些无法被完整匹配命中，必须在这里整体替换，绝不原样发到 SSE。
   */
  public static String maskFinal(String input) {
    if (input == null || input.isEmpty()) return input;
    String value = maskCompleted(input);
    value = TRUNCATED_TOKEN.matcher(value).replaceAll("$1***MASKED***");
    value = TRUNCATED_KV.matcher(value).replaceAll("$1***MASKED***");
    value = TRUNCATED_URL_CREDENTIALS.matcher(value).replaceAll("://***MASKED***");
    return value;
  }

  /**
   * 严格限长脱敏：先脱敏，再硬截断到不超过 {@code maxChars}。
   *
   * <p>与 {@link #maskAndTruncate} 的区别是**绝不抬高下限、绝不追加后缀**：调用方明确要求
   * varchar(32/64/80) 这类短列时，多一个字符就是数据库列宽溢出。{@code maskAndTruncate} 的 256 字
   * 保底只适用于长文本快照。
   */
  public static String maskToMaxLength(String input, int maxChars) {
    if (input == null) return null;
    if (maxChars < 1) throw new IllegalArgumentException("限长必须为正数。");
    String masked = mask(input);
    return masked.length() <= maxChars ? masked : masked.substring(0, maxChars);
  }
}
