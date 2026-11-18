package dev.workbench.agent;

import dev.workbench.framework.tool.ToolSpecification;
import dev.workbench.workspace.WorkspaceService;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** 单文件写入要求任务写授权，并在工作区服务内核对文件版本。 */
public final class WorkspaceWriteTool {
  static final ToolSpecification.Parameter EXPECTED =
      new ToolSpecification.Parameter("expectedSha256", "最近一次读取获得的 SHA-256");
  static final ToolSpecification.Parameter CONTENT =
      new ToolSpecification.Parameter("content", "完整的新文件内容");

  private WorkspaceWriteTool() {}

  static ToolSpecification<WriteReceipt> specification(
      WorkspaceService service, String id, String scope) {
    return new ToolSpecification<>(
        "write_authorized_file",
        "保存用户授权的文件；必须提供读取时的 SHA-256，不可访问其他文件。",
        List.of(EXPECTED, CONTENT),
        List.of(id, scope),
        "修改用户授权的工作区文件。",
        arguments -> validateInput(arguments.get("expectedSha256"), arguments.get("content")),
        (arguments, check) ->
            writeFile(
                service, id, scope, arguments.get("expectedSha256"), arguments.get("content")),
        (arguments, receipt) -> "已修改授权文件，尚需审查和测试。",
        (arguments, receipt) ->
            dev.workbench.framework.tool.ResourceVersion.encode(
                java.util.Map.of(scope, receipt.sha256())));
  }

  /** 只检查输入，不读取或修改工作区。 */
  static void validateInput(String expected, String content) {
    if (expected == null
        || !expected.matches("[0-9a-fA-F]{64}")
        || content == null
        || content.getBytes(StandardCharsets.UTF_8).length > 1_000_000)
      throw new IllegalArgumentException("写入参数无效。");
  }

  static WriteReceipt writeFile(
      WorkspaceService service, String id, String scope, String expected, String content) {
    return new WriteReceipt(service.write(id, scope, expected, content).sha256());
  }

  /** 写入回执保留新版本摘要。 */
  public record WriteReceipt(String sha256) {}
}
