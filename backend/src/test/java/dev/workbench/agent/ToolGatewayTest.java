package dev.workbench.agent;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.workbench.domain.EventType;
import dev.workbench.domain.ModelKind;
import dev.workbench.domain.ToolKind;
import dev.workbench.framework.agent.execution.AgentRuntime;
import dev.workbench.framework.tool.ToolEffect;
import dev.workbench.framework.tool.ToolGateway;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

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
                  throw new dev.workbench.workspace.WorkspaceVersionConflict("版本不一致");
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
    var writer =
        new WorkspaceWriteTool(
            workspace,
            "workspace",
            "file",
            new ObjectMapper().findAndRegisterModules(),
            runtime,
            new AtomicReference<RuntimeException>());
    var result = new ObjectMapper().readTree(writer.write("invalid-hash", "新内容"));
    assertEquals("REJECTED", result.path("status").asText());
    assertEquals("INVALID_ARGUMENT", result.path("error").path("code").asText());
    org.mockito.Mockito.verifyNoInteractions(workspace);
  }

  @Test
  void workspaceWriteFailureRequiresReconciliation() {
    var workspace = org.mockito.Mockito.mock(dev.workbench.workspace.WorkspaceService.class);
    var runtime = new StubRuntime();
    var failure = new AtomicReference<RuntimeException>();
    var writer =
        new WorkspaceWriteTool(
            workspace,
            "workspace",
            "file",
            new ObjectMapper().findAndRegisterModules(),
            runtime,
            failure);
    String hash = "a".repeat(64);
    org.mockito.Mockito.when(workspace.write("workspace", "file", hash, "新内容"))
        .thenThrow(new IllegalStateException("底层状态不确定"));
    var error =
        assertThrows(
            dev.workbench.framework.tool.UncertainToolEffectException.class,
            () -> writer.write(hash, "新内容"));
    assertSame(error, failure.get());
    assertNull(runtime.rejected);
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

  private static final class StubRuntime implements AgentRuntime {
    final UUID id = UUID.randomUUID();
    final ArrayList<EventType> events = new ArrayList<>();
    String result;
    String rejected;

    public void beforeModel(ModelKind model) {}

    public void modelResult(Integer input, Integer output) {}

    public void definitionResolved(
        dev.workbench.framework.agent.definition.AgentDefinitionRef ref, String sha256) {}

    public void skillsLoaded(java.util.List<dev.workbench.framework.skill.SkillRef> skills) {}

    public void toolsLoaded(java.util.Map<ToolKind, Integer> tools) {}

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
  }
}
