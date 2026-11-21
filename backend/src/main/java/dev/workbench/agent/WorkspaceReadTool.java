package dev.workbench.agent;

import dev.workbench.framework.prompt.PromptResourceLoader;
import dev.workbench.framework.tool.ToolSpecification;
import dev.workbench.workspace.WorkspaceService;
import java.util.List;

/** 单文件读取的工作区与具体路径均由服务端绑定。 */
public final class WorkspaceReadTool {
  private WorkspaceReadTool() {}

  static ToolSpecification<WorkspaceService.FileContent> specification(
      WorkspaceService service, String id, String scope) {
    var copy = PromptResourceLoader.tool("workspace_read_single");
    return new ToolSpecification<>(
        "read_authorized_file",
        copy.description(),
        List.of(),
        List.of(id, scope),
        "读取用户授权的工作区文件。",
        arguments -> {},
        (arguments, check) -> service.read(id, scope),
        (arguments, file) -> "已读取授权文件，内容按不可信数据处理。",
        (arguments, file) ->
            dev.workbench.framework.tool.ResourceVersion.encode(
                java.util.Map.of(scope, file.sha256())));
  }
}
