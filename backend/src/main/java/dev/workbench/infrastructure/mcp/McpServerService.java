package dev.workbench.infrastructure.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.workbench.auth.AuthService;
import dev.workbench.domain.ExecutionScopeResolver;
import dev.workbench.domain.WorkbenchIds;
import dev.workbench.domain.WorkbenchTime;
import dev.workbench.framework.outbound.OutboundUrlGuard;
import dev.workbench.framework.secret.SecretCipher;
import dev.workbench.persistence.entity.UserMcpServerEntity;
import dev.workbench.persistence.entity.UserMcpToolEntity;
import dev.workbench.persistence.repository.UserMcpServerRepository;
import dev.workbench.persistence.repository.UserMcpToolRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * MCP Server 与工具清单管理；凭据只入密文，响应永不回显。
 *
 * <p>权限与出站边界：STDIO 传输会在宿主机启动子进程，多用户模式下仅管理员可注册与探测； STREAMABLE_HTTP 端点必须通过 {@link OutboundUrlGuard}
 * 的地址校验，用户也不能覆盖客户端保留的 HTTP 头（{@code Host}、{@code Content-Length}、{@code Transfer-Encoding}、{@code
 * Connection} 等）。
 */
@Service
public class McpServerService {
  private final UserMcpServerRepository servers;
  private final UserMcpToolRepository tools;
  private final McpClientManager clients;
  private final ExecutionScopeResolver scopes;
  private final ObjectMapper json;
  private final AuthService auth;
  private final OutboundUrlGuard guard;
  private final SecretCipher cipher;

  @org.springframework.beans.factory.annotation.Autowired
  public McpServerService(
      UserMcpServerRepository servers,
      UserMcpToolRepository tools,
      McpClientManager clients,
      ExecutionScopeResolver scopes,
      ObjectMapper json,
      AuthService auth,
      OutboundUrlGuard guard) {
    this(servers, tools, clients, scopes, json, auth, guard, SecretCipher.shared());
  }

  /** 单元测试可注入固定主密钥，避免触碰本机 config/master.key。 */
  McpServerService(
      UserMcpServerRepository servers,
      UserMcpToolRepository tools,
      McpClientManager clients,
      ExecutionScopeResolver scopes,
      ObjectMapper json,
      AuthService auth,
      OutboundUrlGuard guard,
      SecretCipher cipher) {
    this.servers = servers;
    this.tools = tools;
    this.clients = clients;
    this.scopes = scopes;
    this.json = json;
    this.auth = auth;
    this.guard = guard == null ? OutboundUrlGuard.strict() : guard;
    this.cipher = cipher;
  }

  public List<ServerView> list() {
    return servers.findByOwnerScopeKeyOrderByUpdatedAtDesc(scope()).stream()
        .map(this::view)
        .toList();
  }

  @Transactional
  public ServerView create(ServerRequest request) {
    String slug = slug(request.slug());
    if (servers.existsByOwnerScopeKeyAndSlug(scope(), slug))
      throw new IllegalArgumentException("该 MCP Server 别名已存在。");
    String transport = transport(request.transport());
    requireStdioAdmin(transport);
    String now = WorkbenchTime.now();
    var saved =
        servers.saveAndFlush(
            new UserMcpServerEntity(
                WorkbenchIds.newId(),
                scope(),
                name(request.name()),
                slug,
                transport,
                blank(request.command()),
                writeArgs(request.args()),
                endpoint(request.endpointUrl(), transport),
                encrypt(headers(request.headers())),
                encrypt(request.env()),
                request.enabled() == null || request.enabled(),
                now,
                now));
    return view(saved);
  }

  @Transactional
  public ServerView update(String id, ServerRequest request) {
    var row = own(id);
    String transport = transport(request.transport());
    requireStdioAdmin(transport);
    row.update(
        name(request.name()),
        transport,
        blank(request.command()),
        writeArgs(request.args()),
        endpoint(request.endpointUrl(), transport),
        request.headers() == null ? row.getHeadersCipher() : encrypt(headers(request.headers())),
        request.env() == null ? row.getEnvCipher() : encrypt(request.env()),
        request.enabled() == null ? row.isEnabled() : request.enabled(),
        WorkbenchTime.now());
    clients.evict(id);
    return view(servers.saveAndFlush(row));
  }

  @Transactional
  public void delete(String id) {
    var row = own(id);
    tools.deleteByServerId(id);
    servers.delete(row);
    clients.evict(id);
  }

  /** 一键探测 initialize + tools/list，并缓存工具 Schema。 */
  @Transactional
  public ServerView probe(String id) {
    var row = own(id);
    requireStdioAdmin(row.getTransport());
    var probed = clients.probe(row);
    tools.deleteByServerId(id);
    String now = WorkbenchTime.now();
    for (var descriptor : probed) {
      tools.save(
          new UserMcpToolEntity(
              WorkbenchIds.newId(),
              id,
              descriptor.name(),
              descriptor.description(),
              descriptor.inputSchemaJson(),
              descriptor.riskLevel(),
              true,
              now,
              now));
    }
    return view(row);
  }

  @Transactional
  public ToolView updateTool(String serverId, String toolId, String riskLevel, Boolean enabled) {
    var server = own(serverId);
    var tool = tools.findById(toolId).orElseThrow(() -> new IllegalArgumentException("MCP 工具不存在。"));
    if (!server.getId().equals(tool.getServerId()))
      throw new IllegalArgumentException("MCP 工具不属于该 Server。");
    String risk =
        riskLevel == null || riskLevel.isBlank()
            ? tool.getRiskLevel()
            : riskLevel.strip().toUpperCase(java.util.Locale.ROOT);
    if (!List.of("LOW", "HIGH").contains(risk))
      throw new IllegalArgumentException("MCP 工具风险等级只能是 LOW 或 HIGH。");
    tool.update(risk, enabled == null ? tool.isEnabled() : enabled, WorkbenchTime.now());
    return toolView(tools.saveAndFlush(tool));
  }

  private UserMcpServerEntity own(String id) {
    return servers
        .findByIdAndOwnerScopeKey(id, scope())
        .orElseThrow(() -> new IllegalArgumentException("MCP Server 不存在。"));
  }

  private ServerView view(UserMcpServerEntity row) {
    return new ServerView(
        row.getId(),
        row.getName(),
        row.getSlug(),
        row.getTransport(),
        row.getCommand(),
        readArgs(row.getArgsJson()),
        row.getEndpointUrl(),
        row.isEnabled(),
        row.getHeadersCipher() != null && !row.getHeadersCipher().isBlank(),
        row.getEnvCipher() != null && !row.getEnvCipher().isBlank(),
        row.getCreatedAt(),
        row.getUpdatedAt(),
        tools.findByServerIdOrderByNameAsc(row.getId()).stream()
            .map(McpServerService::toolView)
            .toList());
  }

  private static ToolView toolView(UserMcpToolEntity row) {
    return new ToolView(
        row.getId(),
        row.getName(),
        row.getDescription(),
        row.getInputSchemaJson(),
        row.getRiskLevel(),
        row.isEnabled());
  }

  private String scope() {
    return scopes.current().ownerScopeKey();
  }

  /**
   * STDIO 会在宿主机执行白名单命令；多用户模式下只允许管理员注册与探测。
   *
   * <p>尚未初始化账号的单机本地模式没有用户体系，保持既有本地能力；初始化后普通用户一律 403。
   */
  private void requireStdioAdmin(String transport) {
    if (!"STDIO".equals(transport)) return;
    if (!auth.initialized()) return;
    auth.requireAdmin();
  }

  /** STREAMABLE_HTTP 端点必须通过统一出站校验；STDIO 不涉及网络地址。 */
  private String endpoint(String value, String transport) {
    String text = blank(value);
    if (!"STREAMABLE_HTTP".equals(transport)) return text;
    if (text == null) throw new IllegalArgumentException("STREAMABLE_HTTP 必须填写端点地址。");
    return guard.validate(text);
  }

  /** 保留头与换行注入在保存阶段即拒绝，避免把非法配置带进运行期。 */
  private static Map<String, String> headers(Map<String, String> values) {
    if (values == null || values.isEmpty()) return values;
    var sanitized = new LinkedHashMap<String, String>();
    for (var entry : values.entrySet()) {
      String name = entry.getKey() == null ? "" : entry.getKey().strip();
      String headerValue = entry.getValue() == null ? "" : entry.getValue();
      if (name.isEmpty()
          || name.indexOf('\r') >= 0
          || name.indexOf('\n') >= 0
          || headerValue.indexOf('\r') >= 0
          || headerValue.indexOf('\n') >= 0
          || HttpMcpTransport.isReservedHeader(name))
        throw new IllegalArgumentException(
            dev.workbench.framework.i18n.LocalizedMessages.get("outbound.mcp.header"));
      sanitized.put(name, headerValue);
    }
    return sanitized;
  }

  private String encrypt(Map<String, String> values) {
    if (values == null || values.isEmpty()) return null;
    try {
      return cipher.encrypt(json.writeValueAsString(values));
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalArgumentException("MCP 凭据无法加密。", e);
    }
  }

  private String writeArgs(List<String> args) {
    try {
      return json.writeValueAsString(args == null ? List.of() : List.copyOf(args));
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalArgumentException("MCP 参数无法保存。", e);
    }
  }

  private List<String> readArgs(String raw) {
    try {
      return raw == null || raw.isBlank()
          ? List.of()
          : json.readValue(
              raw, json.getTypeFactory().constructCollectionType(List.class, String.class));
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      return List.of();
    }
  }

  private static String slug(String value) {
    String slug = value == null ? "" : value.strip().toLowerCase(java.util.Locale.ROOT);
    if (!slug.matches("[a-z0-9-]{1,32}"))
      throw new IllegalArgumentException("MCP Server 别名必须为 1-32 位小写字母、数字或连字符。");
    return slug;
  }

  private static String name(String value) {
    String name = value == null ? "" : value.strip();
    if (name.isBlank() || name.length() > 120)
      throw new IllegalArgumentException("MCP Server 名称无效。");
    return name;
  }

  private static String transport(String value) {
    String transport = value == null ? "" : value.strip().toUpperCase(java.util.Locale.ROOT);
    if (!List.of("STDIO", "STREAMABLE_HTTP").contains(transport))
      throw new IllegalArgumentException("MCP 传输类型无效。");
    return transport;
  }

  private static String blank(String value) {
    return value == null || value.isBlank() ? null : value.strip();
  }

  public record ServerRequest(
      String name,
      String slug,
      String transport,
      String command,
      List<String> args,
      String endpointUrl,
      Map<String, String> headers,
      Map<String, String> env,
      Boolean enabled) {}

  public record ServerView(
      String id,
      String name,
      String slug,
      String transport,
      String command,
      List<String> args,
      String endpointUrl,
      boolean enabled,
      boolean headersConfigured,
      boolean envConfigured,
      String createdAt,
      String updatedAt,
      List<ToolView> tools) {}

  public record ToolView(
      String id,
      String name,
      String description,
      String inputSchemaJson,
      String riskLevel,
      boolean enabled) {}
}
