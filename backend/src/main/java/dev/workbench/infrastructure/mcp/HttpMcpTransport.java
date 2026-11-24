package dev.workbench.infrastructure.mcp;

import dev.workbench.framework.i18n.LocalizedMessages;
import dev.workbench.framework.outbound.OutboundHttpClients;
import dev.workbench.framework.outbound.OutboundUrlGuard;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * STREAMABLE_HTTP 传输：JSON-RPC POST，兼容 text/event-stream 的 data 行。
 *
 * <p>安全约束：端点必须通过 {@link OutboundUrlGuard} 的严格校验（公网或显式开启的回环），禁止 自动跟随 302，用户自定义 Header 不能覆盖 {@code
 * Host}/{@code Content-Length}/{@code Transfer-Encoding}/{@code Connection} 等保留头，也不能携带换行做头注入。
 */
public final class HttpMcpTransport implements McpTransport {
  /** 由客户端自行决定或会影响消息边界的保留头，用户配置一律忽略。 */
  private static final Set<String> RESERVED_HEADERS =
      Set.of(
          "host",
          "content-length",
          "transfer-encoding",
          "connection",
          "upgrade",
          "te",
          "trailer",
          "expect",
          "content-type",
          "accept",
          "proxy-authorization",
          "proxy-authenticate");

  /** 单次 JSON-RPC 响应上限，防止恶意/异常服务端用超大响应耗尽内存。 */
  private static final int MAX_RESPONSE_CHARS = 4 * 1024 * 1024;

  private final HttpClient client;
  private final URI endpoint;
  private final Map<String, String> headers;
  private final Duration timeout;

  public HttpMcpTransport(
      String endpointUrl, Map<String, String> headers, Duration timeout, OutboundUrlGuard guard) {
    if (endpointUrl == null || endpointUrl.isBlank())
      throw new IllegalArgumentException("MCP HTTP 端点不能为空。");
    var checked = guard == null ? OutboundUrlGuard.strict() : guard;
    this.endpoint = URI.create(checked.requireSafe(endpointUrl));
    this.headers = sanitize(headers);
    this.timeout = timeout;
    this.client = OutboundHttpClients.shared();
  }

  /** 判断是否为客户端保留头；服务层保存配置时复用同一判定，避免两处规则漂移。 */
  static boolean isReservedHeader(String name) {
    return name != null && RESERVED_HEADERS.contains(name.strip().toLowerCase(Locale.ROOT));
  }

  /** 拒绝保留头与头注入；返回值保持用户配置顺序。 */
  private static Map<String, String> sanitize(Map<String, String> headers) {
    if (headers == null || headers.isEmpty()) return Map.of();
    var sanitized = new LinkedHashMap<String, String>();
    for (var entry : headers.entrySet()) {
      String name = entry.getKey() == null ? "" : entry.getKey().strip();
      String value = entry.getValue() == null ? "" : entry.getValue();
      if (name.isEmpty()
          || name.indexOf('\r') >= 0
          || name.indexOf('\n') >= 0
          || value.indexOf('\r') >= 0
          || value.indexOf('\n') >= 0)
        throw new IllegalArgumentException(LocalizedMessages.get("outbound.mcp.header"));
      if (RESERVED_HEADERS.contains(name.toLowerCase(Locale.ROOT)))
        throw new IllegalArgumentException(LocalizedMessages.get("outbound.mcp.header"));
      sanitized.put(name, value);
    }
    return Map.copyOf(sanitized);
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
    String body = response.body() == null ? "" : response.body();
    if (body.length() > MAX_RESPONSE_CHARS)
      throw new IllegalStateException("MCP HTTP 响应超过允许的最大长度。");
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
