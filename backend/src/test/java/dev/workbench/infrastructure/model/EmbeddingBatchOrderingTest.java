package dev.workbench.infrastructure.model;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import dev.workbench.auth.AuthService;
import dev.workbench.framework.outbound.OutboundUrlGuard;
import dev.workbench.modelconnection.ModelConnectionService;
import dev.workbench.modelconnection.RagEmbeddingSettings;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 批量向量化的顺序与完整性约束。
 *
 * <p>反例对应 `P-01` 的配套缺陷：兼容端点可能乱序返回 {@code data}，或返回条数不足。前者会让向量与
 * 错误的文本配对，后者会让后续按位置写入的向量列整体错位。两种都必须按失败处理。
 */
class EmbeddingBatchOrderingTest {
  @TempDir Path tempDir;

  private final ModelConnectionService connections = mockConnections();
  private final AuthService auth = mockAuth();
  private HttpServer server;
  private final AtomicReference<String> response = new AtomicReference<>();

  private static ModelConnectionService mockConnections() {
    return org.mockito.Mockito.mock(ModelConnectionService.class);
  }

  private static AuthService mockAuth() {
    return org.mockito.Mockito.mock(AuthService.class);
  }

  @BeforeEach
  void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/v1/embeddings",
        exchange -> {
          byte[] body = response.get().getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
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

  private OpenAiCompatibleEmbeddingProvider provider() {
    var settings =
        new RagEmbeddingSettings(
            tempDir.resolve("settings.json").toString(),
            true,
            "",
            "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
            "text-embedding-3-small",
            3,
            "env-key",
            connections,
            auth,
            OutboundUrlGuard.allowLocalLoopback());
    return new OpenAiCompatibleEmbeddingProvider(
        connections, settings, OutboundUrlGuard.allowLocalLoopback(), "env-key", 5, 32);
  }

  private static String embedding(double first) {
    return "[" + first + ",0,0]";
  }

  @Test
  void outOfOrderDataIsRealignedByIndex() {
    response.set(
        "{\"data\":[{\"index\":1,\"embedding\":"
            + embedding(0.2)
            + "},{\"index\":0,\"embedding\":"
            + embedding(0.1)
            + "}]}");

    var vectors = provider().embedAll(List.of("第一段", "第二段"));

    assertEquals(2, vectors.size());
    assertEquals(0.1f, vectors.get(0)[0], 1e-6);
    assertEquals(0.2f, vectors.get(1)[0], 1e-6);
  }

  @Test
  void missingItemsFailFastInsteadOfShiftingPositions() {
    response.set("{\"data\":[{\"index\":0,\"embedding\":" + embedding(0.1) + "}]}");

    var vectors = provider().embedAll(List.of("第一段", "第二段"));

    assertTrue(vectors.isEmpty(), "条数不足必须整体失败，不能按位置错配");
  }

  @Test
  void mixedDimensionsFailFast() {
    response.set(
        "{\"data\":[{\"index\":0,\"embedding\":[0.1,0.2,0.3]},"
            + "{\"index\":1,\"embedding\":[0.1,0.2]}]}");

    var vectors = provider().embedAll(List.of("第一段", "第二段"));

    assertTrue(vectors.isEmpty(), "维度不一致必须整体失败");
  }

  @Test
  void emptyInputNeverCallsTheEndpoint() {
    response.set("{\"data\":[]}");

    assertTrue(provider().embedAll(List.of()).isEmpty());
  }
}
