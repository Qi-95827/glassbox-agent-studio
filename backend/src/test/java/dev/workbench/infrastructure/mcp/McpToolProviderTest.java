package dev.workbench.infrastructure.mcp;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.workbench.persistence.entity.UserMcpServerEntity;
import dev.workbench.persistence.entity.UserMcpToolEntity;
import dev.workbench.persistence.repository.UserMcpServerRepository;
import dev.workbench.persistence.repository.UserMcpToolRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** MCP 工具命名空间、归属隔离与启用过滤。 */
class McpToolProviderTest {
  @Test
  void exposesEnabledToolsWithNamespacedName() {
    var servers = mock(UserMcpServerRepository.class);
    var tools = mock(UserMcpToolRepository.class);
    var ownership = mock(dev.workbench.ownership.OwnershipService.class);
    var server =
        new UserMcpServerEntity(
            "server-1", "owner-a", "Demo", "demo", "STREAMABLE_HTTP", null, "[]",
            "http://127.0.0.1/mcp", null, null, true, "now", "now");
    var tool =
        new UserMcpToolEntity(
            "tool-1", "server-1", "echo", "回显", "{\"type\":\"object\"}", "HIGH", true, "now", "now");
    when(ownership.taskIdOfRun("run-1")).thenReturn("task-1");
    when(ownership.owner(dev.workbench.ownership.OwnershipService.TASK, "task-1"))
        .thenReturn(Optional.of("owner-a"));
    when(servers.findByOwnerScopeKeyAndEnabledTrueOrderByUpdatedAtDesc("owner-a"))
        .thenReturn(List.of(server));
    when(tools.findByServerIdAndEnabledTrueOrderByNameAsc("server-1")).thenReturn(List.of(tool));
    var provider = new McpToolProvider(servers, tools, ownership);
    var bindings = provider.bindingsForRun("run-1");
    assertEquals(1, bindings.size());
    assertEquals("mcp::demo::echo", bindings.getFirst().exposedName());
    assertEquals("HIGH", bindings.getFirst().riskLevel());
  }

  @Test
  void unknownRunDoesNotExposeAnyMcpTool() {
    var servers = mock(UserMcpServerRepository.class);
    var tools = mock(UserMcpToolRepository.class);
    var ownership = mock(dev.workbench.ownership.OwnershipService.class);
    when(ownership.taskIdOfRun("missing")).thenThrow(new IllegalArgumentException("not found"));
    assertTrue(new McpToolProvider(servers, tools, ownership).bindingsForRun("missing").isEmpty());
  }
}
