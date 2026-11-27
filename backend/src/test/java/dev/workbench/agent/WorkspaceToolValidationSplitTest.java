package dev.workbench.agent;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.workbench.domain.EventType;
import dev.workbench.domain.ModelKind;
import dev.workbench.domain.Scenario;
import dev.workbench.domain.ToolKind;
import dev.workbench.framework.agent.assembly.AgentAssemblySnapshot;
import dev.workbench.framework.agent.execution.AgentRuntime;
import dev.workbench.framework.tool.AgentToolContext;
import dev.workbench.framework.trace.GlassboxModels;
import dev.workbench.infrastructure.agentscope.AgentToolRegistry;
import dev.workbench.workspace.AuthorizedFileSet;
import dev.workbench.workspace.WorkspaceService;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.ToolCallParam;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * 工作区工具的两个校验器必须各管一件事：Schema 只认参数字段，路径白名单只认授权集合。
 *
 * <p>这样网关才能把「参数填错」与「路径越界」分别记到 ARGUMENT_SCHEMA / PATH_WHITELIST，而不是双重 DENY。
 */
class WorkspaceToolValidationSplitTest {
  private final WorkspaceService service = mock(WorkspaceService.class);
  private final AuthorizedFileSet scope =
      new AuthorizedFileSet(AuthorizedFileSet.normalizeScope(List.of("src/app.js")));

  @Test
  void scopedReadSchemaAcceptsAnAuthorizedPathWhileScopeCheckAllowsIt() {
    var spec = WorkspaceScopedReadTool.specification(service, "ws-1", scope, "zh-CN");
    assertNotNull(spec.scopeValidate(), "路径校验必须单独声明，不能混进 Schema 闭包");
    assertDoesNotThrow(() -> spec.validate().accept(Map.of("path", "src/app.js")));
    assertDoesNotThrow(() -> spec.scopeValidate().accept(Map.of("path", "src/app.js")));
  }

  @Test
  void outOfScopePathPassesSchemaButIsRejectedByTheScopeCheck() {
    var spec = WorkspaceScopedReadTool.specification(service, "ws-1", scope, "zh-CN");
    Map<String, String> outOfScope = Map.of("path", "../etc/passwd");

    // 参数本身格式合法：Schema 门不得代替路径门给出结论。
    assertDoesNotThrow(() -> spec.validate().accept(outOfScope));
    assertThrows(
        IllegalArgumentException.class, () -> spec.scopeValidate().accept(outOfScope));
  }

  @Test
  void blankPathIsRejectedBySchemaBeforeTheScopeCheckRuns() {
    var spec = WorkspaceScopedReadTool.specification(service, "ws-1", scope, "zh-CN");
    assertThrows(
        IllegalArgumentException.class, () -> spec.validate().accept(Map.of("path", "  ")));
  }

  @Test
  void scopedWriteSplitsContentValidationFromPathWhitelist() {
    var spec = WorkspaceScopedWriteTool.specification(service, "ws-1", scope, "zh-CN");
    assertNotNull(spec.scopeValidate());

    Map<String, String> invalidSha =
        Map.of("path", "src/app.js", "expectedSha256", "not-a-sha", "content", "text");
    assertThrows(IllegalArgumentException.class, () -> spec.validate().accept(invalidSha));

    Map<String, String> unauthorized =
        Map.of("path", "src/other.js", "expectedSha256", "a".repeat(64), "content", "text");
    assertThrows(
        IllegalArgumentException.class, () -> spec.scopeValidate().accept(unauthorized));
  }

  @Test
  void realToolkitChainAttributesBlankPathAndInvalidShaToSchemaNotWhitelist() {
    var readRuntime = new RecordingRuntime();
    invokeRealToolkit(readRuntime, "read_authorized_file", Map.of("path", "   "));
    assertEquals("DENY", gateStatus(readRuntime, "ARGUMENT_SCHEMA"), "空白路径应判为 Schema 失败");
    assertEquals("SKIP", gateStatus(readRuntime, "PATH_WHITELIST"), "空白路径不应误报路径越界");

    var writeRuntime = new RecordingRuntime();
    invokeRealToolkit(
        writeRuntime,
        "write_authorized_file",
        Map.of("path", "src/app.js", "expectedSha256", "not-a-sha", "content", "text"));
    assertEquals("DENY", gateStatus(writeRuntime, "ARGUMENT_SCHEMA"), "非法 SHA 应判为 Schema 失败");
    assertEquals("SKIP", gateStatus(writeRuntime, "PATH_WHITELIST"), "参数校验失败不应进入路径白名单");
  }

  @Test
  void realGatewayAdapterAttributesNonStringAndMissingPathToSchemaNotWhitelist() {
    for (var badInput : List.<Map<String, Object>>of(Map.of("path", 123), Map.of())) {
      var runtime = new RecordingRuntime();
      invokeRealToolDirect(runtime, "read_authorized_file", badInput);
      assertEquals("DENY", gateStatus(runtime, "ARGUMENT_SCHEMA"), "输入 " + badInput + " 应判为 Schema 失败");
      assertEquals("SKIP", gateStatus(runtime, "PATH_WHITELIST"), "输入 " + badInput + " 不应误报路径越界");
    }
  }

  @Test
  void realGatewayChainPassesSchemaAndBlamesPathWhitelistForOutOfScopePath() {
    var runtime = new RecordingRuntime();
    invokeRealToolkit(runtime, "read_authorized_file", Map.of("path", "../etc/passwd"));
    assertEquals("PASS", gateStatus(runtime, "ARGUMENT_SCHEMA"));
    assertEquals("DENY", gateStatus(runtime, "PATH_WHITELIST"));
  }

  private io.agentscope.core.tool.Toolkit assembleRealToolkit(
      ObjectMapper json, RecordingRuntime runtime) {
    var providers = new BuiltInToolProviders();
    var registry =
        new AgentToolRegistry(
            List.of(
                providers.workspaceReadProvider(service),
                providers.workspaceWriteProvider(service)));
    return registry.assemble(
        Map.of(ToolKind.WORKSPACE_READ, 2, ToolKind.WORKSPACE_WRITE, 2),
        new AgentToolContext(
            json,
            "ws-1",
            List.of("src/app.js", "src/b.js"),
            Scenario.NORMAL,
            runtime,
            new AtomicReference<>(),
            List.of("src/app.js", "src/b.js"),
            "zh-CN",
            "run-1"));
  }

  private void invokeRealToolkit(
      RecordingRuntime runtime, String toolName, Map<String, Object> input) {
    try {
      var json = new ObjectMapper().findAndRegisterModules();
      var toolkit = assembleRealToolkit(json, runtime);
      var block =
          ToolUseBlock.builder()
              .id("call-1")
              .name(toolName)
              .input(input)
              .content(json.writeValueAsString(input))
              .build();
      var result =
          toolkit.callTool(ToolCallParam.builder().toolUseBlock(block).input(input).build()).block();
      assertNotNull(result);
      assertNotNull(runtime.trace, "应记录工具安检轨迹，实际返回：" + result.getState() + " " + result.getOutput());
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private void invokeRealToolDirect(
      RecordingRuntime runtime, String toolName, Map<String, Object> input) {
    try {
      var json = new ObjectMapper().findAndRegisterModules();
      var toolkit = assembleRealToolkit(json, runtime);
      var tool = toolkit.getTool(toolName);
      assertNotNull(tool);
      var block =
          ToolUseBlock.builder()
              .id("call-1")
              .name(toolName)
              .input(input)
              .content(json.writeValueAsString(input))
              .build();
      var result =
          tool.callAsync(ToolCallParam.builder().toolUseBlock(block).input(input).build()).block();
      assertNotNull(result);
      assertNotNull(runtime.trace, "应记录工具安检轨迹，实际返回：" + result.getState() + " " + result.getOutput());
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static String gateStatus(RecordingRuntime runtime, String gate) {
    assertNotNull(runtime.trace, "应记录工具安检轨迹");
    var check =
        runtime.trace.gatewayChecks().stream()
            .filter(item -> gate.equals(item.gate()))
            .findFirst()
            .orElse(null);
    assertNotNull(check, "缺少安检门 " + gate + "：" + runtime.trace.gatewayChecks());
    return check.status();
  }

  private static final class RecordingRuntime implements AgentRuntime {
    private GlassboxModels.ToolCallTrace trace;

    @Override
    public void beforeModel(ModelKind modelName) {}

    @Override
    public void modelResult(Integer inputTokens, Integer outputTokens) {}

    @Override
    public void assemblyResolved(AgentAssemblySnapshot.Components components) {}

    @Override
    public UUID beforeTool(ToolKind tool, int version, String fingerprint) {
      return UUID.randomUUID();
    }

    @Override
    public void check() {}

    @Override
    public void event(EventType type, String message) {}

    @Override
    public void toolResult(String result) {}

    @Override
    public void toolRejected(String safeMessage) {}

    @Override
    public void toolTrace(UUID callId, GlassboxModels.ToolCallTrace trace) {
      this.trace = trace;
    }
  }
}
