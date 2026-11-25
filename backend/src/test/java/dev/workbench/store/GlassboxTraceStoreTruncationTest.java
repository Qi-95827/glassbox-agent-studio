package dev.workbench.store;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.workbench.framework.trace.GlassboxModels;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

/**
 * 玻璃盒载荷超过单条上限时必须按条目裁剪，落库结果仍能完整反序列化。
 *
 * <p>反例对应 `F-09`：旧实现先序列化再按字符截断，会把 JSON 从中间切开，`modelPayloadsByRun` 读取时 直接反序列化失败，安检 DENY 记录也随之丢失。
 */
@DataJpaTest
@org.springframework.test.context.ContextConfiguration(
    classes = dev.workbench.app.WorkbenchApplication.class)
@Import({GlassboxTraceStore.class, ObjectMapper.class})
class GlassboxTraceStoreTruncationTest {
  @Autowired GlassboxTraceStore traces;

  private static List<GlassboxModels.PromptMessage> hugeMessages() {
    var messages = new ArrayList<GlassboxModels.PromptMessage>();
    var body = "长文本载荷".repeat(2_000);
    for (int index = 0; index < 20; index++)
      messages.add(
          new GlassboxModels.PromptMessage("USER", "m" + index, body + index, body.length()));
    return messages;
  }

  @Test
  void oversizedModelPayloadIsStructurallyTruncatedAndReadable() {
    String runId = UUID.randomUUID().toString();
    String callId = UUID.randomUUID().toString();
    traces.recordModelPayload(
        runId,
        callId,
        new GlassboxModels.ModelCallPayload(
            hugeMessages(),
            List.of(new GlassboxModels.ToolSchemaView("write", "写文件", "{}")),
            "ok",
            List.of(new GlassboxModels.ToolCallDecision("t1", "write", "{}")),
            false,
            null));

    var payloads = traces.modelPayloadsByRun(runId);

    var stored = payloads.get(callId);
    assertNotNull(stored, "超限载荷也必须落库且可读取");
    assertTrue(stored.requestMessages().size() < 20, "必须按条目裁剪而不是截断字符");
    assertTrue(stored.truncated(), "裁剪后必须标记 truncated");
    assertEquals(1, stored.requestTools().size(), "未超限的小列表必须完整保留");
  }

  @Test
  void oversizedGatewayChecksKeepDenyEntriesReadable() {
    String runId = UUID.randomUUID().toString();
    String callId = UUID.randomUUID().toString();
    var checks = new ArrayList<GlassboxModels.GatewayCheck>();
    checks.add(new GlassboxModels.GatewayCheck("PATH_WHITELIST", "路径白名单", "DENY", "越权路径"));
    var body = "细节".repeat(40_000);
    checks.add(new GlassboxModels.GatewayCheck("ARGUMENT_SCHEMA", "参数校验", "PASS", body));

    traces.recordToolTrace(
        runId,
        callId,
        new GlassboxModels.ToolCallTrace("{\"path\":\"a.txt\"}", List.copyOf(checks), body, false));

    var stored = traces.toolTracesByRun(runId).get(callId);
    assertNotNull(stored);
    assertTrue(
        stored.gatewayChecks().stream().anyMatch(check -> "DENY".equals(check.status())),
        "DENY 安检记录不能被裁剪丢失：" + stored.gatewayChecks().size());
  }

  @Test
  void ordinaryPayloadRoundTripsUnchanged() {
    String runId = UUID.randomUUID().toString();
    String callId = UUID.randomUUID().toString();
    traces.recordModelPayload(
        runId,
        callId,
        new GlassboxModels.ModelCallPayload(
            List.of(new GlassboxModels.PromptMessage("USER", null, "正常正文", 4)),
            List.of(),
            "回答",
            List.of(),
            false,
            null));

    var stored = traces.modelPayloadsByRun(runId).get(callId);
    assertNotNull(stored);
    assertFalse(stored.truncated());
    assertEquals("正常正文", stored.requestMessages().getFirst().content());
  }
}
