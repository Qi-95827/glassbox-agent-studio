package dev.workbench.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.workbench.domain.ToolKind;
import dev.workbench.framework.agent.execution.AgentRuntime;
import dev.workbench.framework.tool.ToolContracts;
import dev.workbench.framework.tool.ToolEffect;
import dev.workbench.framework.tool.ToolFingerprint;
import dev.workbench.framework.tool.ToolGateway;
import dev.workbench.workspace.AuthorizedFileSet;
import dev.workbench.workspace.WorkspaceService;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.util.concurrent.atomic.AtomicReference;

/** 多文件模式的写工具；只接受授权集合中的现有文件及该文件的版本摘要。 */
public final class WorkspaceScopedWriteTool {
  private final WorkspaceService service;
  private final String workspaceId;
  private final AuthorizedFileSet scope;
  private final ToolGateway gateway;

  WorkspaceScopedWriteTool(
      WorkspaceService service,
      String workspaceId,
      AuthorizedFileSet scope,
      ObjectMapper json,
      AgentRuntime runtime,
      AtomicReference<RuntimeException> failure) {
    this.service = service;
    this.workspaceId = workspaceId;
    this.scope = scope;
    this.gateway = new ToolGateway(json, runtime, failure);
  }

  @Tool(
      name = "write_authorized_file",
      description = "修改授权列表中的一个现有文件，必须提供该文件的 SHA-256。",
      concurrencySafe = false)
  public String write(
      @ToolParam(name = "path", description = "用户授权列表中的工作区相对路径") String path,
      @ToolParam(name = "expectedSha256", description = "该文件最近一次读取获得的 SHA-256") String expected,
      @ToolParam(name = "content", description = "完整的新文件内容") String content) {
    return gateway.execute(
        ToolKind.WORKSPACE_WRITE,
        ToolContracts.MULTI_FILE_VERSION,
        ToolFingerprint.of(ToolKind.WORKSPACE_WRITE, workspaceId, path, expected, content),
        ToolEffect.WRITE,
        "修改用户授权的工作区文件。",
        receipt -> "已修改授权文件：" + scope.require(path) + "；尚需审查和测试。",
        () -> {
          scope.require(path);
          WorkspaceWriteTool.validateInput(expected, content);
        },
        () ->
            WorkspaceWriteTool.writeFile(
                service, workspaceId, scope.require(path), expected, content));
  }
}
