package dev.workbench.infrastructure.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.workbench.framework.outbound.OutboundUrlGuard;
import dev.workbench.framework.secret.SecretCipher;
import dev.workbench.persistence.entity.UserMcpServerEntity;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 按 Server 配置创建并缓存 MCP 客户端；凭据只在内存中解密。 */
@Component
public class McpClientManager {
  private final ObjectMapper json;
  private final ExecutorService executor;
  private final List<String> allowedCommands;
  private final Duration timeout;
  private final OutboundUrlGuard guard;
  private final SecretCipher cipher = SecretCipher.shared();
  private final Map<String, Holder> clients = new ConcurrentHashMap<>();

  public McpClientManager(
      ObjectMapper json,
      @Qualifier("mcpExecutor") ExecutorService executor,
      OutboundUrlGuard guard,
      @Value("${workbench.mcp.allowed-commands:node,npx,python,python3,uvx,docker}")
          String allowedCommands,
      @Value("${workbench.mcp.timeout-seconds:30}") long timeoutSeconds) {
    this.json = json;
    this.executor = executor;
    this.guard = guard == null ? OutboundUrlGuard.strict() : guard;
    this.allowedCommands =
        Arrays.stream(allowedCommands.split(","))
            .map(String::strip)
            .filter(s -> !s.isBlank())
            .toList();
    this.timeout = Duration.ofSeconds(Math.max(1, timeoutSeconds));
  }

  public McpJsonRpcClient client(UserMcpServerEntity server) {
    String key = server.getId() + ":" + server.getUpdatedAt();
    return clients.compute(
            key,
            (ignored, existing) -> {
              if (existing != null) return existing;
              try {
                return new Holder(build(server));
              } catch (Exception e) {
                throw new IllegalStateException("MCP Server 连接失败：" + e.getMessage(), e);
              }
            })
        .client;
  }

  public List<McpJsonRpcClient.McpToolDescriptor> probe(UserMcpServerEntity server) {
    try {
      return client(server).listTools();
    } catch (Exception e) {
      throw new IllegalStateException("MCP 工具探测失败：" + e.getMessage(), e);
    }
  }

  public String call(UserMcpServerEntity server, String toolName, String argsJson) {
    try {
      return client(server).callTool(toolName, argsJson);
    } catch (Exception e) {
      throw new IllegalStateException("MCP 工具调用失败：" + e.getMessage(), e);
    }
  }

  public void evict(String serverId) {
    clients
        .entrySet()
        .removeIf(
            entry -> {
              if (!entry.getKey().startsWith(serverId + ":")) return false;
              entry.getValue().client.close();
              return true;
            });
  }

  private McpJsonRpcClient build(UserMcpServerEntity server) throws Exception {
    String transport = server.getTransport() == null ? "" : server.getTransport().toUpperCase();
    if ("STDIO".equals(transport)) {
      List<String> args = json.readValue(server.getArgsJson(), List.class);
      Map<String, String> environment = decryptMap(server.getEnvCipher());
      return new McpJsonRpcClient(
          new StdioMcpTransport(
              server.getCommand(), args, environment, allowedCommands, executor, timeout),
          json);
    }
    if ("STREAMABLE_HTTP".equals(transport)) {
      return new McpJsonRpcClient(
          new HttpMcpTransport(
              server.getEndpointUrl(), decryptMap(server.getHeadersCipher()), timeout, guard),
          json);
    }
    throw new IllegalArgumentException("不支持的 MCP 传输类型：" + server.getTransport());
  }

  private Map<String, String> decryptMap(String cipherText) throws Exception {
    if (cipherText == null || cipherText.isBlank()) return Map.of();
    return json.readValue(cipher.decrypt(cipherText), Map.class);
  }

  private record Holder(McpJsonRpcClient client) {}
}
