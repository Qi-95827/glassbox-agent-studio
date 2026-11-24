package dev.workbench.infrastructure.model;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.sun.net.httpserver.HttpServer;
import dev.workbench.auth.AuthService;
import dev.workbench.framework.outbound.OutboundUrlGuard;
import dev.workbench.modelconnection.ModelConnectionService;
import dev.workbench.modelconnection.RagEmbeddingSettings;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Embedding 探活的密钥与错误回显边界。
 *
 * <p>反例对应 S-02：未校验的自定义 Base URL 会拿到服务端 {@code workbench.rag.embedding.api-key}， 第三方的 500
 * 正文会原样回显到前端。
 */
class EmbeddingProbeSecurityTest {
  @TempDir Path tempDir;

  private HttpServer server;
  private final AtomicReference<String> authorization = new AtomicReference<>();
  private final AtomicInteger requests = new AtomicInteger();

  private final ModelConnectionService connections = mock(ModelConnectionService.class);
  private final AuthService auth = mock(AuthService.class);

  @BeforeEach
  void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/v1/embeddings",
        exchange -> {
          requests.incrementAndGet();
          authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
          byte[] body =
              ("{\"error\":\"upstream failed with sk-live-server-secret-123456 and "
                      + "Bearer abcdefghijklmnop\"}")
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(500, body.length);
          try (var out = exchange.getResponseBody()) {
            out.write(body);
          }
        });
    server.start();
  }

  @AfterEach
  void stopServer() {
    server.stop(0);
  }

  private RagEmbeddingSettings settings(String baseUrl, String apiKey) {
    return new RagEmbeddingSettings(
        tempDir.resolve("settings.json").toString(),
        true,
        "",
        baseUrl,
        "text-embedding-3-small",
        3,
        apiKey,
        connections,
        auth,
        OutboundUrlGuard.allowLocalLoopback());
  }

  @Test
  void probeErrorBodyIsMaskedBeforeItReachesTheBrowser() {
    String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    var provider =
        new OpenAiCompatibleEmbeddingProvider(
            connections,
            settings(baseUrl, "server-env-key"),
            OutboundUrlGuard.allowLocalLoopback(),
            "server-env-key",
            5,
            32);

    var probe = provider.probe();

    assertFalse(probe.ok());
    assertEquals("HTTP_500", probe.status());
    assertFalse(probe.detail().contains("sk-live-server-secret-123456"), probe.detail());
    assertFalse(probe.detail().contains("abcdefghijklmnop"), probe.detail());
    assertTrue(probe.detail().contains("MASKED"), probe.detail());
    assertEquals("Bearer server-env-key", authorization.get());
  }

  @Test
  void unvalidatedBaseUrlNeverReceivesTheServerKey() {
    var guard = OutboundUrlGuard.strict();
    var provider =
        new OpenAiCompatibleEmbeddingProvider(
            connections,
            settings("http://169.254.169.254/v1", "server-env-key"),
            guard,
            "server-env-key",
            5,
            32);

    assertFalse(provider.isAvailable());
    var probe = provider.probe();
    assertFalse(probe.ok());
    assertEquals("UNAVAILABLE", probe.status());
    assertEquals(0, requests.get());
    assertNull(authorization.get());
  }

  @Test
  void domainResolvingToMetadataAddressIsUnavailable() {
    OutboundUrlGuard.HostResolver metadata =
        host -> new InetAddress[] {InetAddress.getByName("169.254.169.254")};
    var provider =
        new OpenAiCompatibleEmbeddingProvider(
            connections,
            settings("https://169.254.169.254.nip.io/v1", "server-env-key"),
            new OutboundUrlGuard(false, metadata),
            "server-env-key",
            5,
            32);

    assertFalse(provider.isAvailable());
    assertEquals("UNAVAILABLE", provider.probe().status());
  }

  @Test
  void connectionEndpointUsesValidatedBaseUrl() {
    when(connections.resolveForExecution("c1"))
        .thenReturn(
            new dev.workbench.modelconnection.RuntimeModelConnection(
                "c1",
                "我的连接",
                dev.workbench.modelconnection.ModelProviderType.OPENAI,
                "http://169.254.169.254/v1",
                "text-embedding-3-small",
                "sk-connection",
                null,
                null,
                "CNY"));
    var settings =
        new RagEmbeddingSettings(
            tempDir.resolve("connection.json").toString(),
            true,
            "c1",
            "",
            "",
            3,
            "server-env-key",
            connections,
            auth,
            OutboundUrlGuard.strict());
    var provider =
        new OpenAiCompatibleEmbeddingProvider(
            connections, settings, OutboundUrlGuard.strict(), "server-env-key", 5, 32);

    assertFalse(provider.isAvailable(), "连接里的内网地址同样要被出站校验拦住");
    assertEquals("UNAVAILABLE", provider.probe().status());
  }
}
