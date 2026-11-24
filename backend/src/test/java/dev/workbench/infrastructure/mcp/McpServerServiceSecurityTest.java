package dev.workbench.infrastructure.mcp;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.workbench.auth.AuthService;
import dev.workbench.domain.ExecutionScope;
import dev.workbench.domain.ExecutionScopeResolver;
import dev.workbench.framework.outbound.OutboundUrlGuard;
import dev.workbench.framework.secret.SecretCipher;
import dev.workbench.persistence.entity.UserMcpServerEntity;
import dev.workbench.persistence.repository.UserMcpServerRepository;
import dev.workbench.persistence.repository.UserMcpToolRepository;
import java.net.InetAddress;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** MCP Server 注册与探测的权限边界：STDIO 仅管理员，HTTP 端点必须通过出站校验，保留头不可覆盖。 */
@ExtendWith(MockitoExtension.class)
class McpServerServiceSecurityTest {
  @Mock UserMcpServerRepository servers;
  @Mock UserMcpToolRepository tools;
  @Mock McpClientManager clients;
  @Mock ExecutionScopeResolver scopes;
  @Mock AuthService auth;

  private final ObjectMapper json = new ObjectMapper();
  private final OutboundUrlGuard guard =
      new OutboundUrlGuard(
          false, host -> new InetAddress[] {InetAddress.getByName("93.184.216.34")});

  private McpServerService service;

  @BeforeEach
  void setUp() {
    lenient().when(scopes.current()).thenReturn(new ExecutionScope("USER_A", "USER_A"));
    service =
        new McpServerService(
            servers, tools, clients, scopes, json, auth, guard, new SecretCipher(testKey()));
    lenient().when(servers.existsByOwnerScopeKeyAndSlug(any(), any())).thenReturn(false);
    lenient().when(servers.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
  }

  /** 固定测试主密钥，避免单元测试读取本机 config/master.key。 */
  private static byte[] testKey() {
    byte[] key = new byte[32];
    new java.security.SecureRandom(new byte[] {7, 5, 3}).nextBytes(key);
    return key;
  }

  private static McpServerService.ServerRequest stdio() {
    return new McpServerService.ServerRequest(
        "本地演示", "demo", "STDIO", "node", List.of(), null, null, null, true);
  }

  private static McpServerService.ServerRequest http(
      String endpointUrl, Map<String, String> headers) {
    return new McpServerService.ServerRequest(
        "远程演示", "remote", "STREAMABLE_HTTP", null, null, endpointUrl, headers, null, true);
  }

  private static UserMcpServerEntity stdioRow() {
    return new UserMcpServerEntity(
        "s1", "USER_A", "本地演示", "demo", "STDIO", "node", "[]", null, null, null, true, "t1", "t1");
  }

  @Test
  void normalUserCannotRegisterStdioServer() {
    when(auth.initialized()).thenReturn(true);
    when(auth.requireAdmin()).thenThrow(new AuthService.AccessDeniedException("该操作只允许管理员执行。"));

    assertThrows(AuthService.AccessDeniedException.class, () -> service.create(stdio()));
    verify(servers, never()).saveAndFlush(any());
  }

  @Test
  void stdioRegistrationAllowedBeforeAnyAccountIsInitialized() {
    when(auth.initialized()).thenReturn(false);

    var view = service.create(stdio());
    assertEquals("STDIO", view.transport());
    verify(servers).saveAndFlush(any());
  }

  @Test
  void normalUserCannotProbeStdioServer() {
    when(servers.findByIdAndOwnerScopeKey("s1", "USER_A")).thenReturn(Optional.of(stdioRow()));
    when(auth.initialized()).thenReturn(true);
    when(auth.requireAdmin()).thenThrow(new AuthService.AccessDeniedException("该操作只允许管理员执行。"));

    assertThrows(AuthService.AccessDeniedException.class, () -> service.probe("s1"));
    verify(clients, never()).probe(any());
  }

  @Test
  void adminCanProbeStdioServer() {
    when(servers.findByIdAndOwnerScopeKey("s1", "USER_A")).thenReturn(Optional.of(stdioRow()));
    when(auth.initialized()).thenReturn(true);
    when(auth.requireAdmin()).thenReturn(null);
    when(clients.probe(any())).thenReturn(List.of());

    assertDoesNotThrow(() -> service.probe("s1"));
    verify(clients).probe(any());
  }

  @Test
  void httpServerRejectsMetadataEndpoint() {
    var error =
        assertThrows(
            IllegalArgumentException.class,
            () -> service.create(http("http://169.254.169.254/latest/meta-data", null)));
    assertTrue(
        error.getMessage().contains("内网") || error.getMessage().contains("private"),
        error.getMessage());
    verify(servers, never()).saveAndFlush(any());
  }

  @Test
  void httpServerRejectsPrivateAndIpv6LoopbackEndpoints() {
    assertThrows(
        IllegalArgumentException.class, () -> service.create(http("https://10.5.5.5/mcp", null)));
    assertThrows(
        IllegalArgumentException.class, () -> service.create(http("https://[::1]/mcp", null)));
    assertThrows(
        IllegalArgumentException.class,
        () -> service.create(http("https://[::ffff:169.254.169.254]/mcp", null)));
  }

  @Test
  void httpServerRequiresEndpointUrl() {
    assertThrows(IllegalArgumentException.class, () -> service.create(http(null, null)));
    assertThrows(IllegalArgumentException.class, () -> service.create(http("   ", null)));
  }

  @Test
  void httpServerRejectsReservedHeaders() {
    for (String name : new String[] {"Host", "Content-Length", "Transfer-Encoding", "Connection"}) {
      var headers = new LinkedHashMap<String, String>();
      headers.put(name, "evil");
      assertThrows(
          IllegalArgumentException.class,
          () -> service.create(http("https://mcp.example.test/mcp", headers)),
          name);
    }
  }

  @Test
  void httpServerRejectsHeaderInjection() {
    var headers = new LinkedHashMap<String, String>();
    headers.put("X-Trace", "ok\r\nHost: evil");
    assertThrows(
        IllegalArgumentException.class,
        () -> service.create(http("https://mcp.example.test/mcp", headers)));
  }

  @Test
  void httpServerAcceptsPublicEndpointAndCustomHeaders() {
    var view = service.create(http("https://mcp.example.test/mcp", Map.of("X-Token", "abc")));
    assertEquals("STREAMABLE_HTTP", view.transport());
    assertTrue(view.headersConfigured());
  }

  @Test
  void normalUserKeepsHttpTransportAbility() {
    // 只有 STDIO 收口到管理员；普通用户仍可登记受控 HTTP 端点。
    var view = service.create(http("https://mcp.example.test/mcp", null));
    assertEquals("STREAMABLE_HTTP", view.transport());
    verify(servers).saveAndFlush(any());
  }

  @Test
  void updateFromHttpToStdioAlsoRequiresAdmin() {
    var row =
        new UserMcpServerEntity(
            "s1",
            "USER_A",
            "远程演示",
            "demo",
            "STREAMABLE_HTTP",
            null,
            "[]",
            "https://mcp.example.test/mcp",
            null,
            null,
            true,
            "t1",
            "t1");
    when(servers.findByIdAndOwnerScopeKey("s1", "USER_A")).thenReturn(Optional.of(row));
    when(auth.initialized()).thenReturn(true);
    when(auth.requireAdmin()).thenThrow(new AuthService.AccessDeniedException("该操作只允许管理员执行。"));

    assertThrows(AuthService.AccessDeniedException.class, () -> service.update("s1", stdio()));
  }
}
