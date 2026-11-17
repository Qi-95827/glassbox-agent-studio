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

/** 多文件模式的只读工具；用户指定集合之外的路径在动作前拒绝。 */
public final class WorkspaceScopedReadTool {
  private final WorkspaceService service;
  private final String workspaceId;
  private final AuthorizedFileSet scope;
  private final ToolGateway gateway;

  WorkspaceScopedReadTool(
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
      name = "read_authorized_file",
      description = "读取用户授权列表中的一个相对路径，返回内容和 SHA-256。",
      readOnly = true,
      concurrencySafe = false)
  public String read(@ToolParam(name = "path", description = "用户授权列表中的工作区相对路径") String path) {
    return gateway.execute(
        ToolKind.WORKSPACE_READ,
        ToolContracts.MULTI_FILE_VERSION,
        ToolFingerprint.of(ToolKind.WORKSPACE_READ, workspaceId, path),
        ToolEffect.READ_ONLY,
        "读取用户授权的工作区文件。",
        file -> "已读取授权文件：" + scope.require(path) + "；内容按不可信数据处理。",
        () -> scope.require(path),
        () -> service.read(workspaceId, scope.require(path)));
  }
}
