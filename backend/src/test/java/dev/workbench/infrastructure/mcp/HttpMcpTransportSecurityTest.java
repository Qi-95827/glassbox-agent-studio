package dev.workbench.infrastructure.mcp;

import static org.junit.jupiter.api.Assertions.*;

import dev.workbench.framework.outbound.OutboundUrlGuard;
import java.net.InetAddress;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** MCP STREAMABLE_HTTP 传输的出站边界：端点必须是可达的公网（或显式开启的回环）地址， 用户不能覆盖客户端保留头，也不能通过换行做头注入。 */
class HttpMcpTransportSecurityTest {
  private static final Duration TIMEOUT = Duration.ofSeconds(3);

  /** 固定公网解析结果，测试不依赖真实 DNS。 */
  private static final OutboundUrlGuard PUBLIC_GUARD =
      new OutboundUrlGuard(
          false, host -> new InetAddress[] {InetAddress.getByName("93.184.216.34")});

  @Test
  void rejectsMetadataEndpoint() {
    var error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new HttpMcpTransport(
                    "http://169.254.169.254/latest/meta-data", Map.of(), TIMEOUT, PUBLIC_GUARD));
    assertTrue(
        error.getMessage().contains("内网") || error.getMessage().contains("private"),
        error.getMessage());
  }

  @Test
  void rejectsPrivateAndIpv6LoopbackEndpoints() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new HttpMcpTransport("https://10.1.2.3/mcp", Map.of(), TIMEOUT, PUBLIC_GUARD));
    assertThrows(
        IllegalArgumentException.class,
        () -> new HttpMcpTransport("https://[::1]/mcp", Map.of(), TIMEOUT, PUBLIC_GUARD));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new HttpMcpTransport(
                "https://[::ffff:169.254.169.254]/mcp", Map.of(), TIMEOUT, PUBLIC_GUARD));
  }

  @Test
  void rejectsReservedHeaders() {
    for (String name :
        new String[] {
          "Host", "Content-Length", "Transfer-Encoding", "Connection", "Content-Type"
        }) {
      var headers = new LinkedHashMap<String, String>();
      headers.put(name, "evil");
      assertThrows(
          IllegalArgumentException.class,
          () ->
              new HttpMcpTransport("https://mcp.example.test/mcp", headers, TIMEOUT, PUBLIC_GUARD),
          name);
    }
  }

  @Test
  void rejectsHeaderInjectionThroughLineBreaks() {
    var headers = new LinkedHashMap<String, String>();
    headers.put("X-Trace", "value\r\nHost: evil");
    assertThrows(
        IllegalArgumentException.class,
        () -> new HttpMcpTransport("https://mcp.example.test/mcp", headers, TIMEOUT, PUBLIC_GUARD));
  }

  @Test
  void acceptsPublicEndpointWithCustomHeaders() {
    var headers = new LinkedHashMap<String, String>();
    headers.put("Authorization", "Bearer mcp-token");
    headers.put("X-Workspace", "demo");
    assertDoesNotThrow(
        () -> new HttpMcpTransport("https://mcp.example.test/mcp", headers, TIMEOUT, PUBLIC_GUARD));
  }

  @Test
  void rejectsBlankEndpoint() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new HttpMcpTransport("  ", Map.of(), TIMEOUT, PUBLIC_GUARD));
  }
}
