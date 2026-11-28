package dev.workbench.framework.trace;

import java.util.List;
import java.util.Locale;
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
  /** 受支持的完整掩码占位符；仅当整个字段值恰好等于完整占位符时才豁免，绝不放行「掩码前缀+明文后缀」的混合值。 */
  private static final String MASKED_PLACEHOLDER =
      "\\*\\*\\*MASKED(?:-(?:PRIVATE-KEY|TOKEN|KEY))?\\*\\*\\*";
  /** 合法双引号字符串内容单元（含空格、逗号与反斜杠转义引号），严格以未转义闭双引号为整值边界。 */
  private static final String DOUBLE_QUOTED_ATOM = "(?:[^\"\\\\]|\\\\.)";
  /** 合法单引号字符串内容单元（含空格、逗号与反斜杠转义引号），严格以未转义闭单引号为整值边界。 */
  private static final String SINGLE_QUOTED_ATOM = "(?:[^'\\\\]|\\\\.)";

  /**
   * 流式切点检测规则集：与 {@link #mask(String)} 的规则严格一一对应、彼此独立扫描，
   * 彻底消除重叠盲区；带引号 KV 按对应未转义闭引号界定整值范围，防止引号内的空格或逗号提前切分。
   */
  static final List<Pattern> STREAM_PARTIAL_PATTERNS =
      List.of(
          Pattern.compile(
              "-----BEGIN [A-Z ]*PRIVATE KEY-----(?:[\\s\\S]*?-----END [A-Z ]*PRIVATE KEY-----|[\\s\\S]*)"),
          Pattern.compile("eyJ[A-Za-z0-9_-]*(?:\\.[A-Za-z0-9_-]*){0,2}"),
          Pattern.compile("(?<![A-Za-z0-9])(?:AKIA|ASIA)[0-9A-Z]*"),
          Pattern.compile("(?<![A-Za-z0-9])AIza[A-Za-z0-9_-]*"),
          Pattern.compile("(?<![A-Za-z0-9])(?:gh[pousr]_[A-Za-z0-9_]*|github_pat_[A-Za-z0-9_]*)"),
          Pattern.compile("(?<![A-Za-z0-9])glpat-[A-Za-z0-9_-]*"),
          Pattern.compile("(?i:bearer)\\s+[A-Za-z0-9._~+/=-]*"),
          Pattern.compile("sk-[A-Za-z0-9_-]*"),
          Pattern.compile("://[^/\\s:@'\"]+(?::[^/\\s@'\"]*@?)?"),
          Pattern.compile(
              "(?i:\"?'?"
                  + KV_KEY
                  + "'?\"?\\s*[:=]\\s*(?<!\\\\)\")"
                  + DOUBLE_QUOTED_ATOM
                  + "*(?:\\\\|\"?)"),
          Pattern.compile(
              "(?i:\"?'?"
                  + KV_KEY
                  + "'?\"?\\s*[:=]\\s*(?<!\\\\)')"
                  + SINGLE_QUOTED_ATOM
                  + "*(?:\\\\|'?)"),
          Pattern.compile(
              "(?i:\"?'?" + KV_KEY + "'?\"?)(?:\\s*[:=]\\s*" + KV_VALUE_CHARS + "*|\\s+)"));

  private static final Pattern UNCLOSED_PEM =
      Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*");
  /** 仅锚定流末尾的截断令牌；只对 Bearer 忽略大小写并支持跨行空白，绝不误伤普通英文单词（如 Asia），也不重复叠加已脱敏标记。 */
  private static final Pattern TRUNCATED_TOKEN =
      Pattern.compile(
          "(?<![A-Za-z0-9])((?:sk-|github_pat_|gh[pousr]_|glpat-|AIza|AKIA|ASIA|(?i:bearer)\\s+|eyJ))"
              + "(?!" + MASKED_PLACEHOLDER + "$)[A-Za-z0-9._~+/=-]*$");
  /** 流末尾尚未闭合的双引号凭据值（允许含空格、逗号、转义引号及末尾悬空转义起始符），仅当整值恰好等于完整占位符时豁免。 */
  private static final Pattern TRUNCATED_QUOTED_DOUBLE_KV =
      Pattern.compile(
          "(?i)(\"?'?"
              + KV_KEY
              + "'?\"?\\s*[:=]\\s*(?<!\\\\)\")(?!"
              + MASKED_PLACEHOLDER
              + "$)(?:"
              + DOUBLE_QUOTED_ATOM
              + "+\\\\?|\\\\)$");
  /** 流末尾尚未闭合的单引号凭据值（允许含空格、逗号、转义引号及末尾悬空转义起始符），仅当整值恰好等于完整占位符时豁免。 */
  private static final Pattern TRUNCATED_QUOTED_SINGLE_KV =
      Pattern.compile(
          "(?i)(\"?'?"
              + KV_KEY
              + "'?\"?\\s*[:=]\\s*(?<!\\\\)')(?!"
              + MASKED_PLACEHOLDER
              + "$)(?:"
              + SINGLE_QUOTED_ATOM
              + "+\\\\?|\\\\)$");
  /** 仅锚定流末尾尚未闭合的无引号凭据键值（与整段规则一致支持跨行空白），仅在流异常中断时兜底；仅豁免整值恰好等于完整占位符。 */
  private static final Pattern TRUNCATED_KV =
      Pattern.compile(
          "(?i)(\"?'?" + KV_KEY + "'?\"?\\s*[:=]\\s*\"?'?)(?!" + MASKED_PLACEHOLDER + "$)"
              + KV_VALUE_CHARS
              + "+$");
  /** 流末尾截断的 URL 凭据（缺 {@code @}）；排除 1~5 位纯数字端口（如 {@code http://localhost:8088}），避免误伤普通地址。 */
  private static final Pattern TRUNCATED_URL_CREDENTIALS =
      Pattern.compile("://[^/\\s:@'\"]+:(?!\\d{1,5}$)[^/\\s@'\"]+$");

  /** 双引号敏感键值：严格以未转义闭双引号为整值边界，保护引号内含空格/逗号/转义引号的完整口令并保留相邻 JSON 字段。 */
  private static final Pattern QUOTED_DOUBLE_KV_PATTERN =
      Pattern.compile(
          "(?i)(\"?'?"
              + KV_KEY
              + "'?\"?\\s*[:=]\\s*(?<!\\\\)\")(?!"
              + MASKED_PLACEHOLDER
              + "\")("
              + DOUBLE_QUOTED_ATOM
              + "{6,})(\")");
  /** 单引号敏感键值：严格以未转义闭单引号为整值边界，保护引号内含空格/逗号/转义引号的完整口令并保留相邻字段。 */
  private static final Pattern QUOTED_SINGLE_KV_PATTERN =
      Pattern.compile(
          "(?i)(\"?'?"
              + KV_KEY
              + "'?\"?\\s*[:=]\\s*(?<!\\\\)')(?!"
              + MASKED_PLACEHOLDER
              + "')("
              + SINGLE_QUOTED_ATOM
              + "{6,})(')");
  /** 无引号或未闭合单段敏感键值：值遇到空白、括号、分隔符即停止，绝不吞结构符；仅当整值恰好等于完整占位符时才豁免。 */
  private static final Pattern SECRET_KV_PATTERN =
      Pattern.compile(
          "(?i)(\"?'?(?:api[_-]?key|secret|password|passwd|token|authorization|cookie)'?\"?\\s*[:=]\\s*\"?'?)"
              + "(?!" + MASKED_PLACEHOLDER + "(?!" + KV_VALUE_CHARS + "))"
              + "(" + KV_VALUE_CHARS + "{6,})");

  private SensitiveDataMasker() {}

  public record TruncatedText(String text, boolean truncated) {}

  /**
   * 整段脱敏：严格执行「多行私钥块（完整+未闭合）→ 专项令牌与 URL 凭据 → 带引号整值 KV → 泛化无引号 KV」顺序，
   * 确保泛化规则绝不提前吃掉专项前缀，且带引号敏感值以对应未转义闭引号界定整值边界。
   */
  public static String mask(String input) {
    if (input == null || input.isEmpty()) return input;
    String value = maskBlocksAndTokens(input);
    value = maskKvValues(value);
    return value;
  }

  private static String maskKvValues(String input) {
    String value = QUOTED_DOUBLE_KV_PATTERN.matcher(input).replaceAll("$1***MASKED***$3");
    value = QUOTED_SINGLE_KV_PATTERN.matcher(value).replaceAll("$1***MASKED***$3");
    value = SECRET_KV_PATTERN.matcher(value).replaceAll("$1***MASKED***");
    return value;
  }

  /**
   * 第 1 层（多行块：完整与未闭合 PEM）+ 第 2 层（全部专项令牌与 URL 凭据）；
   * 统一在泛化 {@link #SECRET_KV_PATTERN} 之前执行，消除一切前缀被 KV 吃掉的重叠盲区。
   */
  private static String maskBlocksAndTokens(String input) {
    String value = PEM_PRIVATE_KEY.matcher(input).replaceAll("***MASKED-PRIVATE-KEY***");
    value = UNCLOSED_PEM.matcher(value).replaceAll("***MASKED-PRIVATE-KEY***");
    value = JWT.matcher(value).replaceAll("***MASKED-TOKEN***");
    value = AWS_ACCESS_KEY.matcher(value).replaceAll("***MASKED-KEY***");
    value = GOOGLE_API_KEY.matcher(value).replaceAll("***MASKED-KEY***");
    value = GITHUB_KEY.matcher(value).replaceAll("***MASKED-KEY***");
    value = GITLAB_KEY.matcher(value).replaceAll("***MASKED-KEY***");
    value = BEARER_PATTERN.matcher(value).replaceAll("$1***MASKED***");
    value = API_KEY_PATTERN.matcher(value).replaceAll("sk-***MASKED***");
    value = URL_CREDENTIALS.matcher(value).replaceAll("***MASKED***@");
    return value;
  }

  /**
   * 判断供应商返回的 {@code finishReason} 是否属于输出上限/策略截断等非自然结束。
   *
   * <p>采用自然结束白名单（空值或 {@code stop/end_turn/stop_sequence/tool_calls/tool_use/function_call}）：
   * 凡 {@code length/max_tokens/content_filter/incomplete} 或其他未知非自然原因，均按截断收尾做半截凭据兜底。
   */
  public static boolean isTruncatedFinishReason(String finishReason) {
    if (finishReason == null || finishReason.isBlank()) return false;
    String normalized = finishReason.trim().toLowerCase(Locale.ROOT);
    return switch (normalized) {
      case "stop",
          "end_turn",
          "stop_sequence",
          "tool_calls",
          "tool_use",
          "function_call",
          "finish_reason_stop",
          "completed" -> false;
      default -> true;
    };
  }

  /** 先脱敏再按字符上限截断，避免单次大文件或长上下文拖垮存储与前端渲染。 */
  public static TruncatedText maskAndTruncate(String input, int maxChars) {
    return truncate(input == null ? null : mask(input), maxChars);
  }

  /** 响应快照脱敏截断：按是否因输出上限截断分别走 {@link #maskFinal} 或 {@link #maskCompleted}，与 SSE 收尾严格对齐。 */
  public static TruncatedText maskResponseAndTruncate(
      String input, int maxChars, boolean truncatedByModel) {
    if (input == null) return new TruncatedText(null, false);
    String masked = truncatedByModel ? maskFinal(input) : maskCompleted(input);
    return truncate(masked, maxChars);
  }

  private static TruncatedText truncate(String masked, int maxChars) {
    if (masked == null) return new TruncatedText(null, false);
    int limit = Math.max(256, maxChars);
    if (masked.length() <= limit) return new TruncatedText(masked, false);
    return new TruncatedText(masked.substring(0, limit) + "\n...[已截断]", true);
  }

  public static TruncatedText maskAndTruncate(String input) {
    return maskAndTruncate(input, DEFAULT_MAX_CHARS);
  }

  /**
   * 正常结束收尾：与整段 {@link #mask(String)} 共用同一三层优先级管线（未闭合 PEM 已在 KV 之前处理），
   * 不触发流尾截断规则，避免误伤末尾的普通 {@code http://localhost:8088} 或 {@code token: 100}。
   */
  public static String maskCompleted(String input) {
    return mask(input);
  }

  /**
   * 失败/取消/输出限长收尾：严格遵守「多行块（完整+未闭合 PEM）→ 专项令牌与 URL（完整+流尾截断）→
   * 泛化 KV（完整+流尾截断）」三层顺序，确保任何专项结构都不会被泛化键值规则提前吃掉前缀。
   */
  public static String maskFinal(String input) {
    if (input == null || input.isEmpty()) return input;
    String value = maskBlocksAndTokens(input);
    value = TRUNCATED_TOKEN.matcher(value).replaceAll("$1***MASKED***");
    value = TRUNCATED_URL_CREDENTIALS.matcher(value).replaceAll("://***MASKED***");
    value = maskKvValues(value);
    value = TRUNCATED_QUOTED_DOUBLE_KV.matcher(value).replaceAll("$1***MASKED***");
    value = TRUNCATED_QUOTED_SINGLE_KV.matcher(value).replaceAll("$1***MASKED***");
    value = TRUNCATED_KV.matcher(value).replaceAll("$1***MASKED***");
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
