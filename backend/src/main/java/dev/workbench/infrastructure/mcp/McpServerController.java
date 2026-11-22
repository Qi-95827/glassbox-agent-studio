package dev.workbench.infrastructure.mcp;

import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 用户私有 MCP Server 管理；服务层按当前执行归属隔离，响应不含任何明文凭据。 */
@RestController
@RequestMapping("/api/mcp-servers")
public class McpServerController {
  private final McpServerService servers;

  public McpServerController(McpServerService servers) {
    this.servers = servers;
  }

  public record ToolChange(String riskLevel, Boolean enabled) {}

  @GetMapping
  public List<McpServerService.ServerView> list() {
    return servers.list();
  }

  @PostMapping
  public McpServerService.ServerView create(@RequestBody McpServerService.ServerRequest request) {
    return servers.create(request);
  }

  @PutMapping("/{id}")
  public McpServerService.ServerView update(
      @PathVariable String id, @RequestBody McpServerService.ServerRequest request) {
    return servers.update(id, request);
  }

  @DeleteMapping("/{id}")
  public Map<String, Boolean> delete(@PathVariable String id) {
    servers.delete(id);
    return Map.of("deleted", true);
  }

  @PostMapping("/{id}/probe")
  public McpServerService.ServerView probe(@PathVariable String id) {
    return servers.probe(id);
  }

  @PutMapping("/{id}/tools/{toolId}")
  public McpServerService.ToolView updateTool(
      @PathVariable String id,
      @PathVariable String toolId,
      @RequestBody ToolChange change) {
    return servers.updateTool(id, toolId, change.riskLevel(), change.enabled());
  }

  @org.springframework.web.bind.annotation.ExceptionHandler({
    IllegalArgumentException.class,
    IllegalStateException.class
  })
  @org.springframework.web.bind.annotation.ResponseStatus(
      org.springframework.http.HttpStatus.BAD_REQUEST)
  Map<String, String> invalid(RuntimeException error) {
    return Map.of("message", error.getMessage() == null ? "MCP 请求无效。" : error.getMessage());
  }
}
