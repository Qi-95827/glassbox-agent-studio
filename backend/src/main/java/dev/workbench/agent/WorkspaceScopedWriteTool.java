package dev.workbench.agent;

import dev.workbench.framework.prompt.PromptResourceLoader;
import dev.workbench.framework.tool.ToolSpecification;
import dev.workbench.workspace.AuthorizedFileSet;
import dev.workbench.workspace.WorkspaceService;
import java.util.List;

/** 多文件写入在动作之前核对路径与版本参数。 */
public final class WorkspaceScopedWriteTool {
  private WorkspaceScopedWriteTool() {}

  static ToolSpecification<WorkspaceWriteTool.WriteReceipt> specification(
      WorkspaceService service, String workspaceId, AuthorizedFileSet scope) {
    return specification(service, workspaceId, scope, null);
  }

  static ToolSpecification<WorkspaceWriteTool.WriteReceipt> specification(
      WorkspaceService service, String workspaceId, AuthorizedFileSet scope, String locale) {
    var copy = PromptResourceLoader.tool(locale, "workspace_write_scoped");
    boolean english = PromptResourceLoader.isEnglish(locale);
    return new ToolSpecification<>(
        "write_authorized_file",
        copy.description(),
        List.of(
            new ToolSpecification.Parameter("path", copy.parameter("path")),
            new ToolSpecification.Parameter("expectedSha256", copy.parameter("expectedSha256")),
            new ToolSpecification.Parameter("content", copy.parameter("content"))),
        List.of(workspaceId),
        english
            ? "Modify a workspace file authorized by the user."
            : "修改用户授权的工作区文件。",
        arguments -> {
          String path = arguments.get("path");
          if (path == null || path.isBlank())
            throw new IllegalArgumentException(
                english ? "The file path must not be blank." : "文件路径不能为空。");
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
        (arguments, receipt) ->
            english
                ? "Authorized file modified: "
                    + scope.require(arguments.get("path"))
                    + "; review and tests are still required."
                : "已修改授权文件：" + scope.require(arguments.get("path")) + "；尚需审查和测试。",
        (arguments, receipt) -> dev.workbench.framework.tool.ResourceVersion.encode(
                java.util.Map.of(scope.require(arguments.get("path")), receipt.sha256())))
        .withScopeValidate(arguments -> scope.require(arguments.get("path")));
  }
}
