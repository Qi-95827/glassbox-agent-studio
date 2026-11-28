package dev.workbench.framework.trace;

import java.util.regex.Matcher;

/**
 * 有状态的流式脱敏器：对任意分帧方式都保证「拼接后的输出」与「整段脱敏」一致。
 *
 * <p>逐帧独立正则脱敏的漏洞是跨帧边界：`sk-` 与主体分处两帧时两帧都不命中，浏览器拼回明文。
 * 这里保留可能还在延续的敏感前缀，只发射已经确定安全的已脱敏前缀；流结束时由 {@link #flush()}
 * 把剩余缓冲经 {@link SensitiveDataMasker#maskFinal(String)} 收尾，因此慢速/逐字输出仍然及时，
 * 且失败/取消时残留的未闭合私钥块或半截凭据也不会原样泄露。
 *
 * <p>形态集合与 {@link SensitiveDataMasker} 共用同一份定义，不再维护两套容易分叉的正则。
 */
public final class StreamingMasker {
  /**
   * 尾部保留窗口。必须大于最长可识别敏感前缀（PEM 的 `-----BEGIN ... PRIVATE KEY-----` 约 31 字符）：
   * 只有当整段前缀足以被识别时，起点才会被纳入「跨边界匹配」判断；否则逐字流下前缀会先被发出去。
   */
  private static final int HOLD_BACK = 64;

  private final StringBuilder pending = new StringBuilder();

  /** 追加一段净增量，返回当前可以安全发送的已脱敏文本（可能为空）。 */
  public synchronized String accept(String fragment) {
    if (fragment == null || fragment.isEmpty()) return "";
    pending.append(fragment);
    if (pending.length() <= HOLD_BACK) return "";
    int cut = pending.length() - HOLD_BACK;
    Matcher matcher = SensitiveDataMasker.STREAM_PARTIAL.matcher(pending);
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

  /**
   * 流正常结束时冲刷剩余缓冲：走与整段等价的 {@link SensitiveDataMasker#maskCompleted(String)}，
   * 保证完整凭据与未闭合私钥均被掩码，同时绝不把末尾普通地址（如 {@code http://localhost:8088}）
   * 或计数（如 {@code token: 100}）误判为中断半截密钥。
   */
  public synchronized String flush() {
    if (pending.length() == 0) return "";
    String remaining = SensitiveDataMasker.maskCompleted(pending.toString());
    pending.setLength(0);
    return remaining;
  }

  /**
   * 流失败/取消时强制冲刷剩余缓冲：在完整脱敏基础上对末尾截断令牌、半截键值与缺 {@code @} URL 凭据兜底掩码。
   */
  public synchronized String flushFinal() {
    if (pending.length() == 0) return "";
    String remaining = SensitiveDataMasker.maskFinal(pending.toString());
    pending.setLength(0);
    return remaining;
  }

  /** 当前尚未发射的原始字符数，供测试与诊断使用。 */
  public synchronized int pendingLength() {
    return pending.length();
  }
}
