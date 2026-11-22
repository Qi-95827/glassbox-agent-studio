package dev.workbench.infrastructure.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** 标准 MCP JSON-RPC 2.0 客户端：initialize / tools/list / tools/call。 */
public final class McpJsonRpcClient implements AutoCloseable {
  private final McpTransport transport;
  private final ObjectMapper json;
  private final AtomicLong ids = new AtomicLong();
  private volatile boolean initialized;

  public McpJsonRpcClient(McpTransport transport, ObjectMapper json) {
    this.transport = transport;
    this.json = json;
  }

  public synchronized void initialize() throws Exception {
    if (initialized) return;
    var params = new LinkedHashMap<String, Object>();
    params.put("protocolVersion", "2025-06-18");
    params.put("capabilities", Map.of());
    params.put("clientInfo", Map.of("name", "glass-box-agent-studio", "version", "1.0"));
    request("initialize", params);
    notification("notifications/initialized", Map.of());
    initialized = true;
  }

  public List<McpToolDescriptor> listTools() throws Exception {
    initialize();
    JsonNode result = request("tools/list", Map.of());
    var tools = new ArrayList<McpToolDescriptor>();
    for (JsonNode item : result.path("tools")) {
      if (item == null || !item.isObject()) continue;
      String name = item.path("name").asText("");
      if (name.isBlank()) continue;
      tools.add(
          new McpToolDescriptor(
              name,
              item.path("description").asText(""),
              item.path("inputSchema").toString(),
              "HIGH"));
    }
    return tools;
  }

  public String callTool(String name, String argumentsJson) throws Exception {
    initialize();
    Map<String, Object> arguments;
    try {
      arguments = json.readValue(argumentsJson == null ? "{}" : argumentsJson, Map.class);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalArgumentException("MCP 工具参数不是有效 JSON 对象。", e);
    }
    JsonNode result =
        request("tools/call", Map.of("name", name, "arguments", arguments == null ? Map.of() : arguments));
    JsonNode content = result.path("content");
    if (content.isArray() && !content.isEmpty()) {
      var text = new StringBuilder();
      for (JsonNode block : content)
        if ("text".equals(block.path("type").asText())) text.append(block.path("text").asText());
      if (!text.isEmpty()) return text.toString();
    }
    return result.toString();
  }

  private JsonNode request(String method, Object params) throws Exception {
    long id = ids.incrementAndGet();
    var payload = new LinkedHashMap<String, Object>();
    payload.put("jsonrpc", "2.0");
    payload.put("id", id);
    payload.put("method", method);
    payload.put("params", params == null ? Map.of() : params);
    JsonNode response = json.readTree(transport.exchange(json.writeValueAsString(payload)));
    if (response == null) throw new IllegalStateException("MCP 返回空响应。");
    if (response.has("error"))
      throw new IllegalStateException(
          "MCP 错误：" + response.path("error").path("message").asText("未知错误"));
    return response.path("result");
  }

  private void notification(String method, Object params) throws Exception {
    var payload = new LinkedHashMap<String, Object>();
    payload.put("jsonrpc", "2.0");
    payload.put("method", method);
    payload.put("params", params == null ? Map.of() : params);
    transport.send(json.writeValueAsString(payload));
  }

  @Override
  public void close() {
    transport.close();
  }

  /** 探测工具描述；risk 由管理端按只读/写入策略重写。 */
  public record McpToolDescriptor(
      String name, String description, String inputSchemaJson, String riskLevel) {}
}
