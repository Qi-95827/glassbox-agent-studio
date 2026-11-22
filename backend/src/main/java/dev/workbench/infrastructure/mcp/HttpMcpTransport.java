package dev.workbench.infrastructure.mcp;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/** STREAMABLE_HTTP 传输：JSON-RPC POST，兼容 text/event-stream 的 data 行。 */
public final class HttpMcpTransport implements McpTransport {
  private final HttpClient client;
  private final URI endpoint;
  private final Map<String, String> headers;
  private final Duration timeout;

  public HttpMcpTransport(
      String endpointUrl, Map<String, String> headers, Duration timeout) {
    if (endpointUrl == null || endpointUrl.isBlank())
      throw new IllegalArgumentException("MCP HTTP 端点不能为空。");
    this.endpoint = URI.create(endpointUrl.strip());
    this.headers = headers == null ? Map.of() : Map.copyOf(headers);
    this.timeout = timeout;
    this.client = HttpClient.newBuilder().connectTimeout(timeout).build();
  }

  @Override
  public String exchange(String request) throws Exception {
    var builder =
        HttpRequest.newBuilder(endpoint)
            .timeout(timeout)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .POST(HttpRequest.BodyPublishers.ofString(request, StandardCharsets.UTF_8));
    headers.forEach(builder::header);
    var response =
        client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    if (response.statusCode() >= 400)
      throw new IllegalStateException("MCP HTTP 调用失败：" + response.statusCode());
    String body = response.body();
    String contentType = response.headers().firstValue("Content-Type").orElse("");
    if (contentType.contains("text/event-stream")) {
      for (String line : body.lines().toList()) {
        if (line.startsWith("data:")) {
          String data = line.substring(5).strip();
          if (data.startsWith("{")) return data;
        }
      }
      throw new IllegalStateException("MCP SSE 响应缺少 JSON-RPC 数据。");
    }
    return body;
  }
}
