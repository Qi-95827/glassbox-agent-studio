package dev.workbench.agent;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.workbench.domain.EventType;
import dev.workbench.domain.ModelKind;
import dev.workbench.domain.Scenario;
import dev.workbench.domain.ToolKind;
import dev.workbench.framework.agent.execution.AgentRuntime;
import dev.workbench.framework.tool.AgentToolContext;
import dev.workbench.infrastructure.agentscope.AgentToolRegistry;
import dev.workbench.framework.tool.ToolEffect;
import dev.workbench.framework.tool.ToolGateway;
import dev.workbench.workspace.WorkspaceService;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.ToolCallParam;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import dev.workbench.domain.WorkspaceVersionConflict;

/** 验证统一结果与实际调用 ID 对齐，失败不能伪装成成功结果。 */
class ToolGatewayTest {
  @Test
  void successProducesVersionedResultLinkedToActualCall() throws Exception {
    ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    StubRuntime runtime = new StubRuntime();
    var failure = new AtomicReference<RuntimeException>();
    var gateway = new ToolGateway(json, runtime, failure);
    String output =
        gateway.execute(
            ToolKind.SIMULATED_INSPECTION,
            dev.workbench.framework.tool.ToolContracts.SIMULATED_VERSION,
            dev.workbench.framework.tool.ToolFingerprint.of(ToolKind.SIMULATED_INSPECTION),
            ToolEffect.READ_ONLY,
            "开始",
            value -> "摘要",
            () -> "模拟观察");
    var result = json.readTree(output);
    assertEquals(runtime.id.toString(), result.path("toolCallId").asText());
    assertEquals("SIMULATED_INSPECTION", result.path("toolId").asText());
    assertEquals(2, result.path("schemaVersion").asInt());
    assertEquals("SUCCEEDED", result.path("status").asText());
    assertEquals("READ_ONLY", result.path("effect").asText());
    assertEquals("模拟观察", result.path("data").asText());
    assertEquals(
        java.util.List.of(EventType.TOOL_STARTED, EventType.TOOL_FINISHED), runtime.events);
    assertEquals("摘要", runtime.result);
    assertNull(failure.get());
  }

  @Test
  void validationErrorsAreStructuredAndDoNotExposeOriginalMessages() throws Exception {
    ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    var runtime = new StubRuntime();
    var failure = new AtomicReference<RuntimeException>();
    var gateway = new ToolGateway(json, runtime, failure);
    var actionCalled = new java.util.concurrent.atomic.AtomicBoolean();
    String output =
        gateway.execute(
            ToolKind.WORKSPACE_WRITE,
            dev.workbench.framework.tool.ToolContracts.SINGLE_FILE_VERSION,
            dev.workbench.framework.tool.ToolFingerprint.of(ToolKind.WORKSPACE_WRITE),
            ToolEffect.WRITE,
            "开始",
            value -> "摘要",
            () -> {
              throw new IllegalArgumentException("不应泄露的内部内容");
            },
            () -> {
              actionCalled.set(true);
              return "未执行";
            });
    var result = json.readTree(output);
    assertEquals("REJECTED", result.path("status").asText());
    assertEquals("INVALID_ARGUMENT", result.path("error").path("code").asText());
    assertTrue(result.path("error").path("mayRetryAfterCorrection").asBoolean());
    assertFalse(output.contains("不应泄露的内部内容"));
    assertFalse(actionCalled.get());
    assertNull(failure.get());
    assertNotNull(runtime.rejected);
    assertEquals(
        java.util.List.of(EventType.TOOL_STARTED, EventType.TOOL_REJECTED), runtime.events);

    var second = new StubRuntime();
    var versionGateway = new ToolGateway(json, second, new AtomicReference<RuntimeException>());
    var stale =
        json.readTree(
            versionGateway.execute(
                ToolKind.WORKSPACE_WRITE,
                dev.workbench.framework.tool.ToolContracts.SINGLE_FILE_VERSION,
                dev.workbench.framework.tool.ToolFingerprint.of(ToolKind.WORKSPACE_WRITE),
                ToolEffect.WRITE,
                "开始",
                value -> "摘要",
                () -> {
                  throw new dev.workbench.domain.WorkspaceVersionConflict("版本不一致");
                }));
    assertEquals("STALE_VERSION", stale.path("error").path("code").asText());
  }

  @Test
  void writeExceptionAfterPossibleEffectIsNotReportedAsSafeRejection() {
    var runtime = new StubRuntime();
    var failure = new AtomicReference<RuntimeException>();
    var gateway = new ToolGateway(new ObjectMapper().findAndRegisterModules(), runtime, failure);
    var sideEffect = new java.util.concurrent.atomic.AtomicBoolean();
    var error =
        assertThrows(
            dev.workbench.framework.tool.UncertainToolEffectException.class,
            () ->
                gateway.execute(
                    ToolKind.WORKSPACE_WRITE,
                    dev.workbench.framework.tool.ToolContracts.SINGLE_FILE_VERSION,
                    dev.workbench.framework.tool.ToolFingerprint.of(ToolKind.WORKSPACE_WRITE),
                    ToolEffect.WRITE,
                    "开始",
                    value -> "摘要",
                    () -> {
                      sideEffect.set(true);
                      throw new IllegalArgumentException("动作执行后的异常");
                    }));
    assertTrue(sideEffect.get());
    assertSame(error, failure.get());
    assertNull(runtime.rejected);
    assertNull(runtime.result);
    assertEquals(java.util.List.of(EventType.TOOL_STARTED), runtime.events);
  }

  @Test
  void invalidWriteInputIsRejectedBeforeCallingWorkspace() throws Exception {
    var workspace = org.mockito.Mockito.mock(dev.workbench.workspace.WorkspaceService.class);
    var runtime = new StubRuntime();
    var result =
        new ObjectMapper()
            .readTree(
                callRegisteredWrite(workspace, runtime, new AtomicReference<>(), "invalid-hash"));
    assertEquals("REJECTED", result.path("status").asText());
    assertEquals("INVALID_ARGUMENT", result.path("error").path("code").asText());
    org.mockito.Mockito.verifyNoInteractions(workspace);
  }

  @Test
  void workspaceWriteFailureRequiresReconciliation() {
    var workspace = org.mockito.Mockito.mock(dev.workbench.workspace.WorkspaceService.class);
    var runtime = new StubRuntime();
    var failure = new AtomicReference<RuntimeException>();
    String hash = "a".repeat(64);
    org.mockito.Mockito.when(workspace.write("workspace", "file", hash, "新内容"))
        .thenThrow(new IllegalStateException("底层状态不确定"));
    var error =
        assertThrows(
            dev.workbench.framework.tool.UncertainToolEffectException.class,
            () -> callRegisteredWrite(workspace, runtime, failure, hash));
    assertSame(error, failure.get());
    assertNull(runtime.rejected);
  }

  private static String callRegisteredWrite(
      WorkspaceService workspace,
      AgentRuntime runtime,
      AtomicReference<RuntimeException> failure,
      String hash) {
    var json = new ObjectMapper().findAndRegisterModules();
    var provider = new BuiltInToolProviders().workspaceWriteProvider(workspace);
    var context =
        new AgentToolContext(json, "workspace", List.of("file"), Scenario.NORMAL, runtime, failure);
    var toolkit =
        new AgentToolRegistry(List.of(provider))
            .assemble(Map.of(ToolKind.WORKSPACE_WRITE, 1), context);
    String name = "write_authorized_file";
    var use =
        ToolUseBlock.builder()
            .id("write")
            .name(name)
            .input(Map.of("expectedSha256", hash, "content", "新内容"))
            .build();
    var result =
        toolkit
            .getTool(name)
            .callAsync(ToolCallParam.builder().toolUseBlock(use).input(use.getInput()).build())
            .block();
    assertNotNull(result);
    return ((TextBlock) result.getOutput().getFirst()).getText();
  }

  @Test
  void readonlyActionValidationErrorCanBeCorrected() throws Exception {
    var runtime = new StubRuntime();
    var gateway =
        new ToolGateway(
            new ObjectMapper().findAndRegisterModules(),
            runtime,
            new AtomicReference<RuntimeException>());
    String output =
        gateway.execute(
            ToolKind.WORKSPACE_READ,
            dev.workbench.framework.tool.ToolContracts.SINGLE_FILE_VERSION,
            dev.workbench.framework.tool.ToolFingerprint.of(ToolKind.WORKSPACE_READ),
            ToolEffect.READ_ONLY,
            "开始",
            value -> "摘要",
            () -> {
              throw new IllegalArgumentException("路径不可用");
            });
    var result = new ObjectMapper().readTree(output);
    assertEquals("REJECTED", result.path("status").asText());
    assertFalse(output.contains("路径不可用"));
  }

  @Test
  void failurePreservesCauseAndDoesNotRecordSuccess() {
    var runtime = new StubRuntime();
    var failure = new AtomicReference<RuntimeException>();
    var gateway = new ToolGateway(new ObjectMapper().findAndRegisterModules(), runtime, failure);
    var expected = new IllegalStateException("模拟故障");
    assertSame(
        expected,
        assertThrows(
            IllegalStateException.class,
            () ->
                gateway.execute(
                    ToolKind.WORKSPACE_READ,
                    dev.workbench.framework.tool.ToolContracts.SINGLE_FILE_VERSION,
                    dev.workbench.framework.tool.ToolFingerprint.of(ToolKind.WORKSPACE_READ),
                    ToolEffect.READ_ONLY,
                    "开始",
                    value -> "摘要",
                    () -> {
                      throw expected;
                    })));
    assertSame(expected, failure.get());
    assertNull(runtime.result);
    assertEquals(java.util.List.of(EventType.TOOL_STARTED), runtime.events);
  }

  @Test
  void traceRecordsEffectiveMaskedArgsJsonInsteadOfSha256Fingerprint() throws Exception {
    var runtime = new StubRuntime();
    var gateway =
        new ToolGateway(
            new ObjectMapper().findAndRegisterModules(),
            runtime,
            new AtomicReference<RuntimeException>());
    String fingerprint =
        dev.workbench.framework.tool.ToolFingerprint.of(ToolKind.WORKSPACE_READ, "sort.js");
    String rawArgs =
        "{\"path\":\"sort.js\",\"token\":\"sk-1234567890abcdef\",\"pem\":\"-----BEGIN RSA PRIVATE KEY-----\\nMIIESecret\"}";
    gateway.execute(
        ToolKind.WORKSPACE_READ,
        "WORKSPACE_READ",
        dev.workbench.framework.tool.ToolContracts.SINGLE_FILE_VERSION,
        fingerprint,
        rawArgs,
        ToolEffect.READ_ONLY,
        "开始",
        value -> "摘要",
        () -> {},
        null,
        null,
        null,
        () -> "ok");

    assertNotNull(runtime.lastTrace);
    assertFalse(
        runtime.lastTrace.inputArgs().equals(fingerprint),
        "工具轨迹 inputArgs 不得退化为 SHA-256 指纹");
    var parsed = new ObjectMapper().readTree(runtime.lastTrace.inputArgs());
    assertEquals("sort.js", parsed.get("path").asText(), "实际路径参数必须可读");
    assertFalse(runtime.lastTrace.inputArgs().contains("sk-1234567890abcdef"));
    assertFalse(runtime.lastTrace.inputArgs().contains("MIIESecret"));
  }

  @Test
  void traceRecordsEditedArgsJsonWhenApprovedWithEdits() throws Exception {
    var runtime = new StubRuntime();
    runtime.nextApproval =
        new dev.workbench.framework.tool.ToolApprovalDecision(
            dev.workbench.framework.tool.ToolApprovalAction.APPROVE_WITH_EDITED_ARGS,
            "{\"path\":\"safe.js\",\"content\":\"edited\"}",
            null);
    var gateway =
        new ToolGateway(
            new ObjectMapper().findAndRegisterModules(),
            runtime,
            new AtomicReference<RuntimeException>(),
            ctx ->
                new dev.workbench.framework.tool.ToolRiskAssessment(
                    dev.workbench.framework.tool.ToolRiskLevel.HIGH, "需审批"));
    gateway.execute(
        ToolKind.WORKSPACE_WRITE,
        "WORKSPACE_WRITE",
        dev.workbench.framework.tool.ToolContracts.SINGLE_FILE_VERSION,
        dev.workbench.framework.tool.ToolFingerprint.of(ToolKind.WORKSPACE_WRITE, "orig.js"),
        "{\"path\":\"orig.js\",\"content\":\"orig\"}",
        ToolEffect.WRITE,
        "开始写入",
        value -> "摘要",
        () -> {},
        null,
        edited -> {},
        edited -> "ok",
        () -> "orig");

    assertNotNull(runtime.lastTrace);
    var parsed = new ObjectMapper().readTree(runtime.lastTrace.inputArgs());
    assertEquals("safe.js", parsed.get("path").asText(), "人工改参放行后轨迹应记录最终生效的参数");
    assertEquals("edited", parsed.get("content").asText());
  }

  private static final class StubRuntime implements AgentRuntime {
    final UUID id = UUID.randomUUID();
    final ArrayList<EventType> events = new ArrayList<>();
    String result;
    String rejected;
    dev.workbench.framework.trace.GlassboxModels.ToolCallTrace lastTrace;
    dev.workbench.framework.tool.ToolApprovalDecision nextApproval =
        dev.workbench.framework.tool.ToolApprovalDecision.approve();

    public void beforeModel(ModelKind model) {}

    public void modelResult(Integer input, Integer output) {}

    public void assemblyResolved(
        dev.workbench.framework.agent.assembly.AgentAssemblySnapshot.Components components) {}

    public UUID beforeTool(ToolKind tool, int version, String fingerprint) {
      return id;
    }

    public void check() {}

    public void event(EventType type, String message) {
      events.add(type);
    }

    public void toolResult(String value) {
      result = value;
    }

    public void toolRejected(String value) {
      rejected = value;
    }

    @Override
    public void toolTrace(
        UUID toolCallId, dev.workbench.framework.trace.GlassboxModels.ToolCallTrace trace) {
      this.lastTrace = trace;
    }

    @Override
    public dev.workbench.framework.tool.ToolApprovalDecision approvalDecision(
        String toolName,
        int toolVersion,
        String argsJson,
        dev.workbench.framework.tool.ToolRiskAssessment risk) {
      return nextApproval;
    }
  }
}
