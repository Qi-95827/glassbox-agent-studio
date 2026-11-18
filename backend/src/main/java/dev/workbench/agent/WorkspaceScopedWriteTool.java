package dev.workbench.agent;

import dev.workbench.framework.tool.ToolSpecification;
import dev.workbench.workspace.AuthorizedFileSet;
import dev.workbench.workspace.WorkspaceService;
import java.util.List;

/** 多文件写入在动作之前核对路径与版本参数。 */
public final class WorkspaceScopedWriteTool {
  private WorkspaceScopedWriteTool() {}

  static ToolSpecification<WorkspaceWriteTool.WriteReceipt> specification(
      WorkspaceService service, String workspaceId, AuthorizedFileSet scope) {
    return new ToolSpecification<>(
        "write_authorized_file",
        "修改授权列表中的一个现有文件，必须提供该文件的 SHA-256。",
        List.of(
            WorkspaceScopedReadTool.PATH, WorkspaceWriteTool.EXPECTED, WorkspaceWriteTool.CONTENT),
        List.of(workspaceId),
        "修改用户授权的工作区文件。",
        arguments -> {
          scope.require(arguments.get("path"));
          WorkspaceWriteTool.validateInput(
              arguments.get("expectedSha256"), arguments.get("content"));
        },
        (arguments, check) ->
            WorkspaceWriteTool.writeFile(
                service,
                workspaceId,
                scope.require(arguments.get("path")),
                arguments.get("expectedSha256"),
                arguments.get("content")),
        (arguments, receipt) -> "已修改授权文件：" + scope.require(arguments.get("path")) + "；尚需审查和测试。",
        (arguments, receipt) ->
            dev.workbench.framework.tool.ResourceVersion.encode(
                java.util.Map.of(scope.require(arguments.get("path")), receipt.sha256())));
  }
}
