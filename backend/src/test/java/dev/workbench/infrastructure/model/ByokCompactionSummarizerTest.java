package dev.workbench.infrastructure.model;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.sun.net.httpserver.HttpServer;
import dev.workbench.framework.context.CompactionScope;
import dev.workbench.framework.context.ContextMessage;
import dev.workbench.modelconnection.ModelConnectionService;
import dev.workbench.modelconnection.ModelProviderType;
import dev.workbench.modelconnection.RuntimeModelConnection;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * L2 摘要的连接归属：只用运行显式透传的 BYOK 连接，绝不回退 {@code LOCAL} 默认连接。
 *
 * <p>反例对应 S-04 第 6 项与 F-10：后台线程读默认连接会把敏感对话发往错误账号并盗刷额度， 且该调用完全不进运行预算。
 */
class ByokCompactionSummarizerTest {
  private final ModelConnectionService connections = mock(ModelConnectionService.class);
  private final ByokCompactionSummarizer summarizer =
      new ByokCompactionSummarizer(connections, true);

  private HttpServer server;
  private final AtomicReference<String> authorization = new AtomicReference<>();
  private final AtomicReference<String> body = new AtomicReference<>();

  private static final List<ContextMessage> HISTORY =
      List.of(new ContextMessage("USER", null, "请修复排序函数的边界问题", null));

  @BeforeEach
  void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
          body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          String response =
              "{\"id\":\"chatcmpl-1\",\"object\":\"chat.completion\",\"created\":1,"
                  + "\"model\":\"gpt-4o-mini\",\"choices\":[{\"index\":0,\"message\":"
                  + "{\"role\":\"assistant\",\"content\":\"## confirmed_facts\\n- 目标已确认\"},"
                  + "\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":11,"
                  + "\"completion_tokens\":5,\"total_tokens\":16}}";
          byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, bytes.length);
          try (var out = exchange.getResponseBody()) {
            out.write(bytes);
          }
        });
    server.start();
  }

  @AfterEach
  void stopServer() {
    server.stop(0);
  }

  private RuntimeModelConnection connection() {
    return new RuntimeModelConnection(
        "conn-1",
        "我的连接",
        ModelProviderType.CUSTOM_OPENAI,
        "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
        "gpt-4o-mini",
        "sk-user-connection",
        null,
        null,
        "CNY");
  }

  @Test
  void legacyThreeArgumentCallNeverTouchesDefaultConnection() {
    assertEquals("", summarizer.summarize("目标", "", HISTORY));
    verify(connections, never()).defaultConnectionId();
    verify(connections, never()).resolveForExecution(anyString());
  }

  @Test
  void scopeWithoutConnectionSkipsModelCall() {
    var scope = new CompactionScope("run-1", "USER_A", null, null);

    assertEquals("", summarizer.summarize(scope, "目标", "", HISTORY));
    verify(connections, never()).defaultConnectionId();
    verify(connections, never()).resolveForExecution(anyString());
  }

  @Test
  void usesScopedConnectionAndReportsUsageIntoRunLedger() {
    when(connections.resolveForExecution("conn-1")).thenReturn(connection());
    AtomicReference<Integer> input = new AtomicReference<>();
    AtomicReference<Integer> output = new AtomicReference<>();
    var scope =
        new CompactionScope(
            "run-1",
            "USER_A",
            "conn-1",
            (in, out) -> {
              input.set(in);
              output.set(out);
            });

    String summary = summarizer.summarize(scope, "目标", "", HISTORY);

    assertTrue(summary.contains("confirmed_facts"), summary);
    assertEquals(11, input.get());
    assertEquals(5, output.get());
    assertEquals("Bearer sk-user-connection", authorization.get());
    assertTrue(body.get().contains("请修复排序函数的边界问题"), body.get());
    verify(connections, never()).defaultConnectionId();
    verify(connections).resolveForExecution("conn-1");
  }

  @Test
  void disabledSummarizerNeverCallsModel() {
    var disabled = new ByokCompactionSummarizer(connections, false);
    var scope = new CompactionScope("run-1", "USER_A", "conn-1", null);

    assertFalse(disabled.isAvailable());
    assertEquals("", disabled.summarize(scope, "目标", "", HISTORY));
    verify(connections, never()).resolveForExecution(anyString());
  }

  @Test
  void unavailableConnectionDegradesToEmptySummary() {
    when(connections.resolveForExecution("conn-1")).thenThrow(new IllegalStateException("连接已停用"));
    var scope = new CompactionScope("run-1", "USER_A", "conn-1", null);

    assertEquals("", summarizer.summarize(scope, "目标", "", HISTORY));
  }
}
