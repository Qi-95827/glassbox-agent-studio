package dev.workbench.infrastructure.mcp;

import dev.workbench.persistence.entity.UserMcpServerEntity;
import dev.workbench.persistence.entity.UserMcpToolEntity;
import dev.workbench.persistence.repository.UserMcpServerRepository;
import dev.workbench.persistence.repository.UserMcpToolRepository;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 已启用 MCP 工具的受控工具源。
 *
 * <p>工具名固定为 {@code mcp::<serverSlug>::<toolName>}；所有调用仍必须经过 ToolGateway 六道安检门
 * 与 Gate 5.5 高危审批，模型或 MCP Server 都不能绕过项目授权。
 */
@Component
public class McpToolProvider {
  private final UserMcpServerRepository servers;
  private final UserMcpToolRepository tools;
  private final dev.workbench.ownership.OwnershipService ownership;

  public McpToolProvider(
      UserMcpServerRepository servers,
      UserMcpToolRepository tools,
      dev.workbench.ownership.OwnershipService ownership) {
    this.servers = servers;
    this.tools = tools;
    this.ownership = ownership;
  }

  public List<Binding> bindingsForRun(String runId) {
    if (runId == null || runId.isBlank()) return List.of();
    String taskId;
    try {
      taskId = ownership.taskIdOfRun(runId);
    } catch (RuntimeException e) {
      return List.of();
    }
    String owner =
        ownership
            .owner(dev.workbench.ownership.OwnershipService.TASK, taskId)
            .orElse("LOCAL");
    var result = new ArrayList<Binding>();
    for (var server : servers.findByOwnerScopeKeyAndEnabledTrueOrderByUpdatedAtDesc(owner)) {
      for (var tool : tools.findByServerIdAndEnabledTrueOrderByNameAsc(server.getId())) {
        result.add(new Binding(server, tool));
      }
    }
    return result;
  }

  public record Binding(UserMcpServerEntity server, UserMcpToolEntity tool) {
    public String exposedName() {
      String slug = server.getSlug() == null ? "server" : server.getSlug();
      return "mcp::" + slug + "::" + tool.getName();
    }

    public String riskLevel() {
      return tool.getRiskLevel() == null ? "HIGH" : tool.getRiskLevel();
    }
  }
}
