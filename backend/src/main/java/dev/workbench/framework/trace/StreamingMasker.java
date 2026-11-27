package dev.workbench.framework.trace;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 有状态的流式脱敏器：对任意分帧方式都保证「拼接后的输出」与「整段脱敏」一致。
 *
 * <p>逐帧独立正则脱敏的漏洞是跨帧边界：`sk-` 与主体分处两帧时两帧都不命中，浏览器拼回明文。
 * 这里保留可能还在延续的敏感前缀，只发射已经确定安全的已脱敏前缀；流结束时由 {@link #flush()}
 * 把剩余缓冲完整脱敏后发射，因此慢速/逐字输出仍然及时，且不会退化为全量缓冲。
 *
 * <p>不变量：任何可能构成敏感串（含未完成形态）的起点之前的文本才可发射；跨过发射边界的匹配
 * 一律整体留在缓冲里，等它完结或 flush 时统一脱敏。
 */
public final class StreamingMasker {
  /**
   * 可能尚未完结的敏感形态。刻意放宽到「前缀 + 任意延续」：宁可多留几帧，也不让半个密钥先出去。
   * 最后一个分支覆盖 `-----BEGIN ... PRIVATE KEY-----` 这类跨行块。
   */
  private static final Pattern UNFINISHED_OR_PARTIAL =
      Pattern.compile(
          "(?s)"
              + "sk-[A-Za-z0-9_-]*"
              + "|(?i:bearer)[ \\t]+[A-Za-z0-9._~+/=-]*"
              + "|eyJ[A-Za-z0-9_-]*(?:\\.[A-Za-z0-9_-]*)*"
              + "|(?i:(?:api[_-]?key|secret|password|passwd|token|authorization|cookie))[ \\t]*[:=][ \\t]*\"?[^\"'\\s,;}\\]。，；！？、）》」』】]*"
              + "|-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*"
              + "|(?:AKIA|ASIA)[0-9A-Z]*"
              + "|AIza[A-Za-z0-9_-]*"
              + "|gh[pousr]_[A-Za-z0-9_]*"
              + "|glpat-[A-Za-z0-9_-]*"
              + "|://[^/\\s:@'\"]+:[^/\\s@'\"]*@?");

  private final StringBuilder pending = new StringBuilder();

  /**
   * 尾部保留窗口。必须大于最长可识别敏感前缀（PEM 的 `-----BEGIN ... PRIVATE KEY-----` 约 31 字符）：
   * 只有当整段前缀足以被识别时，起点才会被纳入「跨边界匹配」判断；否则逐字流下前缀会先被发出去。
   */
  private static final int HOLD_BACK = 64;

  /** 追加一段净增量，返回当前可以安全发送的已脱敏文本（可能为空）。 */
  public synchronized String accept(String fragment) {
    if (fragment == null || fragment.isEmpty()) return "";
    pending.append(fragment);
    if (pending.length() <= HOLD_BACK) return "";
    int cut = pending.length() - HOLD_BACK;
    Matcher matcher = UNFINISHED_OR_PARTIAL.matcher(pending);
    while (matcher.find()) {
      int start = matcher.start();
      if (start >= cut) break;
      // 结束位置越过当前切点：说明这段敏感形态还会被后续帧延续，必须整体留下。
      if (matcher.end() > cut) cut = start;
    }
    if (cut <= 0) return "";
    String safe = SensitiveDataMasker.mask(pending.substring(0, cut));
    pending.delete(0, cut);
    return safe;
  }

  /** 流结束（完成/失败）时强制冲刷剩余缓冲；可重复调用。 */
  public synchronized String flush() {
    if (pending.length() == 0) return "";
    String remaining = SensitiveDataMasker.mask(pending.toString());
    pending.setLength(0);
    return remaining;
  }

  /** 当前尚未发射的原始字符数，供测试与诊断使用。 */
  public synchronized int pendingLength() {
    return pending.length();
  }
}
