package dev.workbench.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.workbench.domain.ToolKind;
import dev.workbench.framework.agent.execution.AgentRuntime;
import dev.workbench.framework.tool.ToolContracts;
import dev.workbench.framework.tool.ToolEffect;
import dev.workbench.framework.tool.ToolFingerprint;
import dev.workbench.framework.tool.ToolGateway;
import dev.workbench.framework.tool.UncertainToolEffectException;
import dev.workbench.workspace.WorkspaceService;
import dev.workbench.workspace.WorkspaceVersionConflict;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.util.concurrent.atomic.AtomicReference;

/** 仅在用户显式授权写入时注册；必须先读取对应文件取得版本摘要。 */
public final class WorkspaceWriteTool {
  private final WorkspaceService service;
  private final String id, scope;
  private final ToolGateway gateway;

  WorkspaceWriteTool(
      WorkspaceService service,
      String id,
      String scope,
      ObjectMapper json,
      AgentRuntime runtime,
      AtomicReference<RuntimeException> failure) {
    this.service = service;
    this.id = id;
    this.scope = scope;
    this.gateway = new ToolGateway(json, runtime, failure);
  }

  @Tool(
      name = "write_authorized_file",
      description = "保存用户授权的文件；必须提供读取时的 SHA-256，不可访问其他文件。",
      concurrencySafe = false)
  public String write(
      @ToolParam(name = "expectedSha256", description = "最近一次读取获得的 SHA-256") String expected,
      @ToolParam(name = "content", description = "完整的新文件内容") String content) {
    return gateway.execute(
        ToolKind.WORKSPACE_WRITE,
        ToolContracts.SINGLE_FILE_VERSION,
        ToolFingerprint.of(ToolKind.WORKSPACE_WRITE, id, scope, expected, content),
        ToolEffect.WRITE,
        "修改用户授权的工作区文件。",
        receipt -> "已修改授权文件，尚需审查和测试。",
        () -> validateInput(expected, content),
        () -> writeFile(service, id, scope, expected, content));
  }

  /** 只检查输入，不读取或修改工作区；调用次数仍计入预算。 */
  static void validateInput(String expected, String content) {
    if (expected == null
        || !expected.matches("[0-9a-fA-F]{64}")
        || content == null
        || content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 1_000_000)
      throw new IllegalArgumentException("写入参数无效。");
  }

  static WriteReceipt writeFile(
      WorkspaceService service, String id, String scope, String expected, String content) {
    try {
      return new WriteReceipt(service.write(id, scope, expected, content).sha256());
    } catch (WorkspaceVersionConflict e) {
      // 此异常只在替换目标文件前抛出，可以安全提示重新读取。
      throw e;
    } catch (RuntimeException e) {
      throw new UncertainToolEffectException(e);
    }
  }

  /** 只向模型返回新版本摘要，不在成功回执中重复文件正文。 */
  public record WriteReceipt(String sha256) {}
}
