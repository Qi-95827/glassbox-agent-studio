package dev.workbench.framework.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.workbench.domain.EventType;
import dev.workbench.domain.ModelKind;
import dev.workbench.domain.ToolKind;
import dev.workbench.framework.agent.assembly.AgentAssemblySnapshot;
import dev.workbench.framework.agent.execution.AgentRuntime;
import dev.workbench.framework.trace.GlassboxModels;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * 工具网关的两道前置门必须分别归因。
 *
 * <p>回归：参数字段/类型校验与业务路径校验混在一个闭包里，仅参数类型不对时也会同时记录
 * `PATH_WHITELIST DENY` 与 `ARGUMENT_SCHEMA DENY`，把「填错参数」误报成「越权路径」。
 */
class ToolGatewayValidationSplitTest {
  private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

  @Test
  void schemaFailureOnlyBlamesArgumentSchemaAndSkipsPathWhitelist() {
    var runtime = new RecordingRuntime();
    var gateway = new ToolGateway(json, runtime, new AtomicReference<>());
    var scopeRan = new AtomicBoolean();

    String output =
        gateway.execute(
            ToolKind.WORKSPACE_WRITE,
            "write_authorized_file",
            1,
            "fingerprint",
            "{\"path\":\"src/a.js\"}",
            ToolEffect.WRITE,
            "开始",
            value -> "摘要",
            () -> scopeRan.set(true),
            () -> {
              throw new IllegalArgumentException("缺少 content 参数");
            },
            null,
            null,
            null,
            List.of("src/a.js"),
            () -> "不应执行");

    assertEquals("REJECTED", read(output).path("status").asText());
    assertEquals("DENY", status(runtime, "ARGUMENT_SCHEMA"));
    assertEquals("SKIP", status(runtime, "PATH_WHITELIST"));
    assertEquals("SKIP", status(runtime, "CANCEL_STATE"));
    assertFalse(scopeRan.get(), "参数 Schema 未通过时不应先跑路径白名单");
    assertFalse(
        detail(runtime, "PATH_WHITELIST").contains("缺少 content"),
        "参数错误不得写进路径门：" + detail(runtime, "PATH_WHITELIST"));
  }

  @Test
  void pathFailureOnlyBlamesPathWhitelistAfterSchemaPasses() {
    var runtime = new RecordingRuntime();
    var gateway = new ToolGateway(json, runtime, new AtomicReference<>());
    var schemaRan = new AtomicBoolean();

    String output =
        gateway.execute(
            ToolKind.WORKSPACE_READ,
            "read_authorized_file",
            1,
            "fingerprint",
            "{\"path\":\"../etc/passwd\"}",
            ToolEffect.READ_ONLY,
            "开始",
            value -> "摘要",
            () -> {
              throw new IllegalArgumentException("路径不在授权范围：../etc/passwd");
            },
            () -> schemaRan.set(true),
            null,
            null,
            null,
            List.of(),
            () -> "不应执行");

    assertEquals("REJECTED", read(output).path("status").asText());
    assertTrue(schemaRan.get(), "参数格式合法时应先通过 Schema 校验再进入路径门");
    assertEquals("PASS", status(runtime, "ARGUMENT_SCHEMA"));
    assertEquals("DENY", status(runtime, "PATH_WHITELIST"));
    assertEquals("SKIP", status(runtime, "CANCEL_STATE"));
    assertFalse(read(output).toString().contains("不应执行"));
  }

  @Test
  void toolsWithoutPathsReportThePathGateAsSkippedInsteadOfFakePass() throws Exception {
    var runtime = new RecordingRuntime();
    var gateway = new ToolGateway(json, runtime, new AtomicReference<>());
    var validated = new AtomicBoolean();

    String output =
        gateway.execute(
            ToolKind.SIMULATED_INSPECTION,
            ToolContracts.SIMULATED_VERSION,
            ToolFingerprint.of(ToolKind.SIMULATED_INSPECTION),
            ToolEffect.READ_ONLY,
            "开始",
            value -> "摘要",
            () -> validated.set(true),
            () -> "模拟观察");

    assertEquals("SUCCEEDED", read(output).path("status").asText());
    assertTrue(validated.get());
    assertEquals("SKIP", status(runtime, "PATH_WHITELIST"));
    assertEquals("PASS", status(runtime, "ARGUMENT_SCHEMA"));
  }

  private com.fasterxml.jackson.databind.JsonNode read(String output) {
    try {
      return json.readTree(output);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static String status(RecordingRuntime runtime, String gate) {
    var check = check(runtime, gate);
    assertNotNull(check, "缺少安检门 " + gate + "：" + runtime.trace);
    return check.status();
  }

  private static String detail(RecordingRuntime runtime, String gate) {
    return check(runtime, gate).detail();
  }

  private static GlassboxModels.GatewayCheck check(RecordingRuntime runtime, String gate) {
    return runtime.trace.gatewayChecks().stream()
        .filter(item -> gate.equals(item.gate()))
        .findFirst()
        .orElse(null);
  }

  /** 只保留本用例需要的观测：完整安检门明细与事件顺序。 */
  private static final class RecordingRuntime implements AgentRuntime {
    private final ArrayList<EventType> events = new ArrayList<>();
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
    public void event(EventType type, String message) {
      events.add(type);
    }

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
