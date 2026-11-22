package dev.workbench.infrastructure.mcp;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Mock MCP Server：验证 initialize、tools/list 与 tools/call 的 JSON-RPC 往返。 */
class McpJsonRpcClientTest {
  @Test
  void initializesListsToolsAndCallsThroughHttpJsonRpc() throws Exception {
    var json = new ObjectMapper();
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/mcp",
        exchange -> {
          String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          var node = json.readTree(body);
          String method = node.path("method").asText("");
          String response;
          if (!node.has("id")) {
            exchange.sendResponseHeaders(202, -1);
            exchange.close();
            return;
          }
          if ("initialize".equals(method)) {
            response =
                "{\"jsonrpc\":\"2.0\",\"id\":"
                    + node.path("id").asLong()
                    + ",\"result\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{}}}";
          } else if ("tools/list".equals(method)) {
            response =
                "{\"jsonrpc\":\"2.0\",\"id\":"
                    + node.path("id").asLong()
                    + ",\"result\":{\"tools\":[{\"name\":\"echo\",\"description\":\"回显\",\"inputSchema\":{\"type\":\"object\"}}]}}";
          } else if ("tools/call".equals(method)) {
            String argument = node.path("params").path("arguments").path("text").asText("");
            response =
                "{\"jsonrpc\":\"2.0\",\"id\":"
                    + node.path("id").asLong()
                    + ",\"result\":{\"content\":[{\"type\":\"text\",\"text\":\""
                    + argument
                    + "\"}]}}";
          } else {
            response =
                "{\"jsonrpc\":\"2.0\",\"id\":"
                    + node.path("id").asLong()
                    + ",\"error\":{\"message\":\"unknown\"}}";
          }
          byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    server.start();
    try (var client =
        new McpJsonRpcClient(
            new HttpMcpTransport(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp",
                Map.of("X-Test", "1"),
                Duration.ofSeconds(5)),
            json)) {
      assertEquals(1, client.listTools().size());
      var tool = client.listTools().getFirst();
      assertEquals("echo", tool.name());
      assertEquals("你好", client.callTool("echo", "{\"text\":\"你好\"}"));
    } finally {
      server.stop(0);
    }
  }
}
