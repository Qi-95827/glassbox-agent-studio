package dev.workbench.framework.outbound;

import dev.workbench.framework.i18n.LocalizedMessages;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 出站 URL 与 DNS 安全校验器：BYOK 模型连接、Embedding 端点与 MCP HTTP 传输共用同一套判定。
 *
 * <p>平台允许的出站目标必须同时满足：
 *
 * <ul>
 *   <li>协议只有 {@code http} / {@code https}，且不得携带 {@code user:pass@}、查询串或片段， 避免「保存的 Base URL + 固定路径」被
 *       {@code ?}/{@code #} 劫持到其他本机服务；
 *   <li>主机字面量（含十进制整数、八/十六进制、简写、IPv4 映射 IPv6 等非标准写法）必须落在公网 单播段；回环、私网、链路本地、云元数据、CGNAT、多播与保留段一律拒绝；
 *   <li>域名必须解析出**全部** A/AAAA 记录并逐条校验，任何一条命中上述网段即整体拒绝；
 *   <li>本机回环地址默认拒绝，只有显式开启 {@code workbench.outbound.allow-loopback} 才放行， 用于本地 Ollama
 *       一类开发态模型服务；普通用户无法借该开关访问其他内网地址。
 * </ul>
 *
 * <p>已知边界：域名在「保存时」校验通过后，真正建立连接前还会由 {@link #requireSafe(String)} 再解析 一次，但仍存在 DNS
 * 重绑定（校验与连接解析结果不同）的残余风险；彻底消除需要把已校验 IP 锁定到 套接字层，本平台当前未实现，故上游 {@code HttpClient} 一律禁用自动重定向以缩小影响面。
 */
@Component
public class OutboundUrlGuard {
  /** 主机名解析器；生产用真实 DNS，单元测试注入固定答案以覆盖内网域名场景。 */
  @FunctionalInterface
  public interface HostResolver {
    InetAddress[] resolve(String host) throws UnknownHostException;
  }

  /** 校验失败原因；调用方据此映射为各自领域的历史错误码。 */
  public enum Reason {
    MISSING,
    INVALID,
    CREDENTIALS,
    QUERY,
    FRAGMENT,
    SCHEME,
    HOST,
    LOOPBACK_DISABLED,
    PRIVATE,
    UNRESOLVED,
    LOOPBACK_HTTP
  }

  /** 受控校验异常：继承 {@link IllegalArgumentException}，HTTP 层统一映射为 400。 */
  public static final class Rejected extends IllegalArgumentException {
    private final Reason reason;

    Rejected(Reason reason, String message) {
      super(message);
      this.reason = reason;
    }

    public Reason reason() {
      return reason;
    }
  }

  private final boolean allowLoopback;
  private final HostResolver resolver;

  @Autowired
  public OutboundUrlGuard(
      @Value("${workbench.outbound.allow-loopback:false}") boolean allowLoopback) {
    this(allowLoopback, InetAddress::getAllByName);
  }

  /** 测试与特殊调用方可直接指定解析器与回环策略。 */
  public OutboundUrlGuard(boolean allowLoopback, HostResolver resolver) {
    this.allowLoopback = allowLoopback;
    this.resolver = resolver == null ? InetAddress::getAllByName : resolver;
  }

  /** 最严格策略：不允许任何回环地址，用于无显式配置的独立单元测试。 */
  public static OutboundUrlGuard strict() {
    return new OutboundUrlGuard(false, InetAddress::getAllByName);
  }

  /** 允许回环地址（本地模型服务），但内网与保留段仍然拒绝。 */
  public static OutboundUrlGuard allowLocalLoopback() {
    return new OutboundUrlGuard(true, InetAddress::getAllByName);
  }

  /**
   * 保存配置时校验：语法、凭据、非标准数值主机与可解析到的地址。
   *
   * <p>主机名暂时无法解析（离线、内网 DNS 未就绪）时放行，避免用户无法预先填写地址；真正发起请求前 必须再调用 {@link
   * #requireSafe(String)}，那一层解析失败即视为不可用。
   */
  public String validate(String url) {
    return check(url, false);
  }

  /** 发起真实出站请求前校验：主机名必须解析成功，且每条记录都落在允许范围内。 */
  public String requireSafe(String url) {
    return check(url, true);
  }

  private String check(String url, boolean strict) {
    if (url == null || url.isBlank()) throw rejected(Reason.MISSING, "outbound.url.required");
    String text = url.strip();
    URI uri;
    try {
      uri = new URI(text);
    } catch (URISyntaxException e) {
      throw rejected(Reason.INVALID, "outbound.url.invalid");
    }
    String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
    if (!"http".equals(scheme) && !"https".equals(scheme))
      throw rejected(Reason.SCHEME, "outbound.url.scheme");
    if (uri.getRawUserInfo() != null)
      throw rejected(Reason.CREDENTIALS, "outbound.url.credentials");
    if (uri.getRawQuery() != null) throw rejected(Reason.QUERY, "outbound.url.query");
    if (uri.getRawFragment() != null) throw rejected(Reason.FRAGMENT, "outbound.url.fragment");
    String host = uri.getHost();
    if (host == null || host.isBlank()) throw rejected(Reason.HOST, "outbound.url.host");
    // URI.getHost() 对 IPv6 字面量保留方括号，后续解析与网段判断统一使用去掉方括号的形式。
    String normalized = host.replace("[", "").replace("]", "").strip();
    if (normalized.isEmpty()) throw rejected(Reason.HOST, "outbound.url.host");

    List<InetAddress> literal = literalAddresses(normalized);
    if (literal != null) {
      checkAddresses(scheme, literal);
      return text;
    }
    if (normalized.chars().allMatch(c -> (c >= '0' && c <= '9') || c == '.'))
      // 形如 127.0.0.256 / 1.2.3.4.5 的“像 IP 但不是 IP”写法不应落到域名解析分支。
      throw rejected(Reason.INVALID, "outbound.url.invalid");
    // 明文 http 只允许显式回环名；该判定不依赖 DNS，离线环境同样生效。
    if ("http".equals(scheme) && !isNamedLoopback(normalized))
      throw rejected(Reason.LOOPBACK_HTTP, "outbound.url.loopbackHttp");
    InetAddress[] resolved;
    try {
      resolved = resolver.resolve(normalized);
    } catch (UnknownHostException e) {
      if (strict) throw rejected(Reason.UNRESOLVED, "outbound.url.unresolved");
      return text;
    }
    if (resolved == null || resolved.length == 0) {
      if (strict) throw rejected(Reason.UNRESOLVED, "outbound.url.unresolved");
      return text;
    }
    checkAddresses(scheme, List.of(resolved));
    return text;
  }

  /** 统一判定一组已解析地址；回环必须全部是回环且开关放行，其余必须全部是公网单播。 */
  private void checkAddresses(String scheme, List<InetAddress> addresses) {
    int loopback = 0;
    for (InetAddress address : addresses) {
      if (address == null) throw rejected(Reason.UNRESOLVED, "outbound.url.unresolved");
      if (isLoopback(address)) {
        loopback++;
        continue;
      }
      if (isBlocked(address)) throw rejected(Reason.PRIVATE, "outbound.url.private");
    }
    if (loopback > 0 && loopback != addresses.size())
      // 同一域名同时解析出回环与公网地址属于典型 DNS 重绑定特征，直接拒绝。
      throw rejected(Reason.PRIVATE, "outbound.url.private");
    if (loopback > 0 && !allowLoopback)
      throw rejected(Reason.LOOPBACK_DISABLED, "outbound.url.loopback");
    if ("http".equals(scheme) && loopback == 0)
      throw rejected(Reason.LOOPBACK_HTTP, "outbound.url.loopbackHttp");
  }

  /**
   * 识别主机字面量（含非标准数值写法）。
   *
   * @return 解析出的地址；不是 IP 字面量时返回 {@code null}，交由 DNS 解析
   */
  private static List<InetAddress> literalAddresses(String host) {
    if (host.indexOf(':') >= 0) {
      // IPv6（含 ::ffff: 映射写法）只能来自字面量，不存在含冒号的合法域名。
      try {
        return List.of(InetAddress.getByName(host));
      } catch (UnknownHostException e) {
        return null;
      }
    }
    byte[] ipv4 = parseIpv4(host);
    if (ipv4 == null) return null;
    try {
      return List.of(InetAddress.getByAddress(ipv4));
    } catch (UnknownHostException e) {
      return null;
    }
  }

  /** 按 inet_aton 的历史写法解析 1~4 段十进制/八进制/十六进制 IPv4。 */
  static byte[] parseIpv4(String host) {
    String text = host.endsWith(".") ? host.substring(0, host.length() - 1) : host;
    if (text.isEmpty()) return null;
    String[] parts = text.split("\\.", -1);
    if (parts.length < 1 || parts.length > 4) return null;
    long[] values = new long[parts.length];
    for (int index = 0; index < parts.length; index++) {
      String part = parts[index];
      if (part.isEmpty() || part.length() > 11) return null;
      try {
        long value;
        if (part.length() > 2 && (part.startsWith("0x") || part.startsWith("0X"))) {
          value = Long.parseLong(part.substring(2), 16);
        } else if (part.length() > 1 && part.charAt(0) == '0') {
          value = Long.parseLong(part.substring(1), 8);
        } else {
          value = Long.parseLong(part, 10);
        }
        if (value < 0) return null;
        values[index] = value;
      } catch (NumberFormatException e) {
        return null;
      }
    }
    // 末段可跨越剩余字节，前面的段必须各自落在 0~255。
    int lastBits =
        switch (parts.length) {
          case 1 -> 32;
          case 2 -> 24;
          case 3 -> 16;
          default -> 8;
        };
    long maxLast = (1L << lastBits) - 1;
    if (values[values.length - 1] > maxLast) return null;
    for (int index = 0; index < values.length - 1; index++) if (values[index] > 255) return null;
    long packed =
        switch (parts.length) {
          case 1 -> values[0];
          case 2 -> (values[0] << 24) | values[1];
          case 3 -> (values[0] << 24) | (values[1] << 16) | values[2];
          default -> (values[0] << 24) | (values[1] << 16) | (values[2] << 8) | values[3];
        };
    if (packed < 0 || packed > 0xFFFFFFFFL) return null;
    return new byte[] {
      (byte) (packed >>> 24), (byte) (packed >>> 16), (byte) (packed >>> 8), (byte) packed
    };
  }

  /** 显式回环主机名；只有这类名字允许在明文 http 下出现（仍需回环开关放行）。 */
  private static boolean isNamedLoopback(String host) {
    String lower = host.toLowerCase(Locale.ROOT);
    return "localhost".equals(lower) || lower.endsWith(".localhost");
  }

  static boolean isLoopback(InetAddress address) {
    byte[] bytes = address.getAddress();
    if (bytes.length == 4) return (bytes[0] & 0xFF) == 127;
    if (bytes.length != 16) return address.isLoopbackAddress();
    for (int index = 0; index < 15; index++) if (bytes[index] != 0) return false;
    return bytes[15] == 1;
  }

  /** 非公网单播段判定；回环由 {@link #isLoopback(InetAddress)} 单独处理。 */
  static boolean isBlocked(InetAddress address) {
    byte[] bytes = address.getAddress();
    return bytes.length == 4 ? isBlockedIpv4(bytes) : isBlockedIpv6(bytes);
  }

  private static boolean isBlockedIpv4(byte[] bytes) {
    int first = bytes[0] & 0xFF;
    int second = bytes[1] & 0xFF;
    int third = bytes[2] & 0xFF;
    if (first == 0) return true; // 0.0.0.0/8
    if (first == 10) return true; // 10.0.0.0/8
    if (first == 169 && second == 254) return true; // 链路本地与云元数据
    if (first == 172 && second >= 16 && second <= 31) return true; // 172.16.0.0/12
    if (first == 192 && second == 168) return true; // 192.168.0.0/16
    if (first == 100 && second >= 64 && second <= 127) return true; // CGNAT 100.64/10
    if (first == 192 && second == 0 && third == 0) return true; // 192.0.0.0/24
    if (first == 192 && second == 0 && third == 2) return true; // TEST-NET-1
    if (first == 198 && (second == 18 || second == 19)) return true; // 198.18.0.0/15 基准测试
    if (first == 198 && second == 51 && third == 100) return true; // TEST-NET-2
    if (first == 203 && second == 0 && third == 113) return true; // TEST-NET-3
    // 224.0.0.0/4 多播、240.0.0.0/4 保留与 255.255.255.255 广播。
    return first >= 224;
  }

  private static boolean isBlockedIpv6(byte[] bytes) {
    boolean allZero = true;
    for (byte value : bytes) {
      if (value != 0) {
        allZero = false;
        break;
      }
    }
    if (allZero) return true; // :: 未指定地址
    if ((bytes[0] & 0xFE) == 0xFC) return true; // fc00::/7 唯一本地地址
    if ((bytes[0] & 0xFF) == 0xFE && (bytes[1] & 0xC0) == 0x80) return true; // fe80::/10
    if ((bytes[0] & 0xFF) == 0xFF) return true; // ff00::/8 多播
    boolean embeddedIpv4 = true;
    for (int index = 0; index < 10; index++) {
      if (bytes[index] != 0) {
        embeddedIpv4 = false;
        break;
      }
    }
    if (embeddedIpv4 && (bytes[10] & 0xFF) == 0xFF && (bytes[11] & 0xFF) == 0xFF)
      // IPv4 映射地址（::ffff:0:0/96）按内嵌 IPv4 判定，防止 ::ffff:169.254.169.254 绕过。
      return isBlockedIpv4(new byte[] {bytes[12], bytes[13], bytes[14], bytes[15]});
    if (embeddedIpv4 && bytes[11] == 0)
      // 已废弃的 IPv4 兼容地址（::a.b.c.d）同样按内嵌 IPv4 判定。
      return isBlockedIpv4(new byte[] {bytes[12], bytes[13], bytes[14], bytes[15]});
    if ((bytes[0] & 0xE0) == 0x20) {
      // 2000::/3 全球单播；仅剔除 2001:db8::/32 文档段。
      if ((bytes[0] & 0xFF) == 0x20
          && (bytes[1] & 0xFF) == 0x01
          && (bytes[2] & 0xFF) == 0x0D
          && (bytes[3] & 0xFF) == 0xB8) return true;
      return false;
    }
    // 其余前缀（NAT64、discard、保留段等）一律视为不可信目标。
    return true;
  }

  private static Rejected rejected(Reason reason, String key) {
    String message = LocalizedMessages.get(key);
    return new Rejected(reason, message == null || message.isBlank() ? key : message);
  }
}
