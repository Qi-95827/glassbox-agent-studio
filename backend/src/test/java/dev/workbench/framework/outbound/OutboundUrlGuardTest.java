package dev.workbench.framework.outbound;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 出站地址与 DNS 安全校验：正常公网放行，各类内网/元数据/畸形写法必须拒绝。 */
class OutboundUrlGuardTest {
  /** 固定返回公网地址的解析器；单元测试不依赖真实 DNS。 */
  private static final OutboundUrlGuard.HostResolver PUBLIC_RESOLVER =
      host -> new InetAddress[] {InetAddress.getByName("93.184.216.34")};

  /** 模拟 169.254.169.254.nip.io 这类“公网域名解析到内网”的写法。 */
  private static final OutboundUrlGuard.HostResolver METADATA_RESOLVER =
      host -> new InetAddress[] {InetAddress.getByName("169.254.169.254")};

  private static final OutboundUrlGuard.HostResolver MIXED_RESOLVER =
      host ->
          new InetAddress[] {
            InetAddress.getByName("127.0.0.1"), InetAddress.getByName("93.184.216.34")
          };

  private static final OutboundUrlGuard.HostResolver FAILING_RESOLVER =
      host -> {
        throw new UnknownHostException(host);
      };

  private static OutboundUrlGuard guard(OutboundUrlGuard.HostResolver resolver) {
    return new OutboundUrlGuard(false, resolver);
  }

  private static OutboundUrlGuard loopbackGuard(OutboundUrlGuard.HostResolver resolver) {
    return new OutboundUrlGuard(true, resolver);
  }

  private static void assertRejected(
      OutboundUrlGuard guard, String url, OutboundUrlGuard.Reason reason) {
    var rejected = assertThrows(OutboundUrlGuard.Rejected.class, () -> guard.requireSafe(url));
    assertEquals(reason, rejected.reason(), url);
  }

  /** 部分畸形写法会被 JDK 的 URI 解析判定为主机无效，与格式无效同属拒绝，二者都可接受。 */
  private static void assertRejectedAs(
      OutboundUrlGuard guard, String url, OutboundUrlGuard.Reason... reasons) {
    var rejected = assertThrows(OutboundUrlGuard.Rejected.class, () -> guard.requireSafe(url), url);
    assertTrue(
        java.util.List.of(reasons).contains(rejected.reason()), url + " 实际原因：" + rejected.reason());
  }

  @Test
  void allowsPublicHttpsEndpoint() {
    assertEquals(
        "https://api.deepseek.com/v1",
        guard(PUBLIC_RESOLVER).requireSafe("https://api.deepseek.com/v1"));
  }

  @Test
  void rejectsIpv6LoopbackLiteralEvenWithHttps() {
    assertRejected(
        guard(PUBLIC_RESOLVER), "https://[::1]/v1", OutboundUrlGuard.Reason.LOOPBACK_DISABLED);
    assertRejected(
        guard(PUBLIC_RESOLVER), "http://[::1]/v1", OutboundUrlGuard.Reason.LOOPBACK_DISABLED);
  }

  @Test
  void rejectsIpv4MappedMetadataLiteral() {
    assertRejected(
        guard(PUBLIC_RESOLVER),
        "https://[::ffff:169.254.169.254]/latest/meta-data",
        OutboundUrlGuard.Reason.PRIVATE);
    assertRejected(
        guard(PUBLIC_RESOLVER),
        "https://[::ffff:127.0.0.1]/v1",
        OutboundUrlGuard.Reason.LOOPBACK_DISABLED);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "https://[fc00::1]/v1",
        "https://[fd12:3456::1]/v1",
        "https://[fe80::1]/v1",
        "https://[::]/v1",
        "https://[ff02::1]/v1"
      })
  void rejectsNonPublicIpv6Literals(String url) {
    assertRejected(guard(PUBLIC_RESOLVER), url, OutboundUrlGuard.Reason.PRIVATE);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "https://10.0.0.8/v1",
        "https://172.20.1.1/v1",
        "https://192.168.1.1/v1",
        "https://169.254.169.254/latest/meta-data",
        "https://100.64.0.1/v1",
        "https://0.0.0.0/v1",
        "https://224.0.0.1/v1",
        "https://198.18.0.1/v1"
      })
  void rejectsPrivateAndReservedIpv4Literals(String url) {
    assertRejected(guard(PUBLIC_RESOLVER), url, OutboundUrlGuard.Reason.PRIVATE);
  }

  @Test
  void rejectsDecimalIntegerIpRepresentation() {
    // 2852039166 等价于 169.254.169.254，inet_aton 历史写法必须同样拦截。
    assertRejected(
        guard(PUBLIC_RESOLVER), "https://2852039166/v1", OutboundUrlGuard.Reason.PRIVATE);
  }

  @Test
  void rejectsShorthandAndHexIpRepresentations() {
    assertRejectedAs(
        guard(PUBLIC_RESOLVER),
        "http://127.1/v1",
        OutboundUrlGuard.Reason.LOOPBACK_DISABLED,
        OutboundUrlGuard.Reason.HOST);
    assertRejected(
        guard(PUBLIC_RESOLVER), "http://0x7f000001/v1", OutboundUrlGuard.Reason.LOOPBACK_DISABLED);
    assertRejected(
        guard(PUBLIC_RESOLVER), "https://0x0a000008/v1", OutboundUrlGuard.Reason.PRIVATE);
    assertRejectedAs(
        guard(PUBLIC_RESOLVER),
        "https://0177.0.0.1/v1",
        OutboundUrlGuard.Reason.LOOPBACK_DISABLED,
        OutboundUrlGuard.Reason.HOST);
  }

  @Test
  void rejectsMetadataDomainResolvedThroughDns() {
    assertRejected(
        guard(METADATA_RESOLVER),
        "https://169.254.169.254.nip.io/latest/meta-data",
        OutboundUrlGuard.Reason.PRIVATE);
  }

  @Test
  void rejectsMixedLoopbackAndPublicDnsAnswers() {
    assertRejected(
        guard(MIXED_RESOLVER), "https://rebind.example.test/v1", OutboundUrlGuard.Reason.PRIVATE);
  }

  @Test
  void rejectsQueryStringAndFragmentToPreventPathHijacking() {
    assertRejected(
        guard(PUBLIC_RESOLVER),
        "https://api.deepseek.com/v1?redirect=http://169.254.169.254",
        OutboundUrlGuard.Reason.QUERY);
    assertRejected(
        guard(PUBLIC_RESOLVER),
        "https://api.deepseek.com/v1#/../admin",
        OutboundUrlGuard.Reason.FRAGMENT);
  }

  @Test
  void rejectsCredentialsInAuthority() {
    assertRejected(
        guard(PUBLIC_RESOLVER),
        "https://user:pass@api.deepseek.com/v1",
        OutboundUrlGuard.Reason.CREDENTIALS);
    assertRejected(
        guard(PUBLIC_RESOLVER),
        "https://token@api.deepseek.com/v1",
        OutboundUrlGuard.Reason.CREDENTIALS);
  }

  @Test
  void rejectsNonHttpSchemes() {
    assertRejected(
        guard(PUBLIC_RESOLVER), "ftp://api.deepseek.com", OutboundUrlGuard.Reason.SCHEME);
    assertRejected(
        guard(PUBLIC_RESOLVER), "file:///data/workbench.mv.db", OutboundUrlGuard.Reason.SCHEME);
    assertRejectedAs(
        guard(PUBLIC_RESOLVER),
        "ext::sh -c id",
        OutboundUrlGuard.Reason.INVALID,
        OutboundUrlGuard.Reason.SCHEME);
  }

  @Test
  void rejectsPlainHttpForPublicHostsWithoutDnsLookup() {
    // 明文 http 只允许回环名：该判定不依赖 DNS，离线环境也必须生效。
    assertRejected(
        guard(FAILING_RESOLVER),
        "http://api.deepseek.com/v1",
        OutboundUrlGuard.Reason.LOOPBACK_HTTP);
  }

  @Test
  void loopbackHttpIsAllowedOnlyWhenExplicitlyEnabled() {
    var localGuard = new OutboundUrlGuard(true, InetAddress::getAllByName);
    var strictLocalGuard = new OutboundUrlGuard(false, InetAddress::getAllByName);
    assertEquals("http://127.0.0.1:11434", localGuard.requireSafe("http://127.0.0.1:11434"));
    assertEquals("http://localhost:11434", localGuard.requireSafe("http://localhost:11434"));
    assertRejected(
        strictLocalGuard, "http://127.0.0.1:11434", OutboundUrlGuard.Reason.LOOPBACK_DISABLED);
    assertRejected(
        strictLocalGuard, "http://localhost:11434", OutboundUrlGuard.Reason.LOOPBACK_DISABLED);
  }

  @Test
  void unresolvableHostIsRejectedBeforeRealRequestsButToleratedWhenSaving() {
    assertRejected(
        guard(FAILING_RESOLVER),
        "https://offline.example.test/v1",
        OutboundUrlGuard.Reason.UNRESOLVED);
    assertEquals(
        "https://offline.example.test/v1",
        guard(FAILING_RESOLVER).validate("https://offline.example.test/v1"));
  }

  @Test
  void rejectsMalformedOrMissingUrls() {
    assertRejected(guard(PUBLIC_RESOLVER), "", OutboundUrlGuard.Reason.MISSING);
    assertRejected(guard(PUBLIC_RESOLVER), "   ", OutboundUrlGuard.Reason.MISSING);
    assertRejected(guard(PUBLIC_RESOLVER), "not a url", OutboundUrlGuard.Reason.INVALID);
    assertRejectedAs(
        guard(PUBLIC_RESOLVER),
        "https://127.0.0.256/v1",
        OutboundUrlGuard.Reason.INVALID,
        OutboundUrlGuard.Reason.HOST);
    assertRejectedAs(
        guard(PUBLIC_RESOLVER),
        "https://1.2.3.4.5/v1",
        OutboundUrlGuard.Reason.INVALID,
        OutboundUrlGuard.Reason.HOST);
  }

  @Test
  void parsesLegacyIpv4Notations() {
    assertArrayEquals(
        new byte[] {(byte) 169, (byte) 254, (byte) 169, (byte) 254},
        OutboundUrlGuard.parseIpv4("2852039166"));
    assertArrayEquals(new byte[] {127, 0, 0, 1}, OutboundUrlGuard.parseIpv4("127.1"));
    assertArrayEquals(new byte[] {(byte) 0x7f, 0, 0, 1}, OutboundUrlGuard.parseIpv4("0x7f.0.0.1"));
    assertNull(OutboundUrlGuard.parseIpv4("api.deepseek.com"));
  }

  @Test
  void blockedAddressClassificationMatchesJavaSemantics() throws Exception {
    assertTrue(OutboundUrlGuard.isBlocked(InetAddress.getByName("10.1.2.3")));
    assertTrue(OutboundUrlGuard.isBlocked(InetAddress.getByName("169.254.169.254")));
    assertTrue(OutboundUrlGuard.isBlocked(InetAddress.getByName("fc00::1")));
    assertFalse(OutboundUrlGuard.isBlocked(InetAddress.getByName("93.184.216.34")));
    assertFalse(OutboundUrlGuard.isBlocked(InetAddress.getByName("2606:4700::1111")));
    assertTrue(OutboundUrlGuard.isLoopback(InetAddress.getByName("127.0.0.1")));
    assertTrue(OutboundUrlGuard.isLoopback(InetAddress.getByName("::1")));
  }

  /** 共享客户端必须禁止自动重定向，避免 302 把已校验地址带到内网。 */
  @Test
  void sharedHttpClientDoesNotFollowRedirects() throws Exception {
    var server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/redirect",
        exchange -> {
          exchange.getResponseHeaders().add("Location", "/target");
          exchange.sendResponseHeaders(302, -1);
          exchange.close();
        });
    server.createContext(
        "/target",
        exchange -> {
          byte[] body = "followed".getBytes(java.nio.charset.StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, body.length);
          try (var out = exchange.getResponseBody()) {
            out.write(body);
          }
        });
    server.start();
    try {
      String base = "http://127.0.0.1:" + server.getAddress().getPort();
      var request =
          HttpRequest.newBuilder(URI.create(base + "/redirect"))
              .timeout(Duration.ofSeconds(5))
              .GET()
              .build();
      var response =
          OutboundHttpClients.shared()
              .send(
                  request,
                  HttpResponse.BodyHandlers.ofString(java.nio.charset.StandardCharsets.UTF_8));
      assertEquals(302, response.statusCode(), "共享客户端不得跟随重定向");
      assertNotEquals("followed", response.body());
    } finally {
      server.stop(0);
    }
  }

  /** 只解析出回环地址的域名同样必须经过回环开关。 */
  @Test
  void resolverReturningOnlyLoopbackRequiresSwitch() {
    OutboundUrlGuard.HostResolver loopbackOnly =
        host -> new InetAddress[] {InetAddress.getByName("127.0.0.1")};
    assertRejected(
        guard(loopbackOnly),
        "https://local.example.test/v1",
        OutboundUrlGuard.Reason.LOOPBACK_DISABLED);
    assertEquals(
        "https://local.example.test/v1",
        loopbackGuard(loopbackOnly).requireSafe("https://local.example.test/v1"));
  }

  /** 多地址域名只要全部是公网单播就放行。 */
  @Test
  void allowsMultiplePublicAddresses() {
    OutboundUrlGuard.HostResolver multiPublic =
        host ->
            new InetAddress[] {
              InetAddress.getByName("93.184.216.34"), InetAddress.getByName("2606:4700::1111")
            };
    assertEquals(
        "https://multi.example.test/v1",
        guard(multiPublic).requireSafe("https://multi.example.test/v1"));
  }
}
