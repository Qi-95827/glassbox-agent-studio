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

  @Test
  void unclosedPemInToolDescriptionAndMessagesStaysMaskedAndKeepsJsonArrayReadableAfterDbRoundTrip() {
    String runId = UUID.randomUUID().toString();
    String modelCallId = UUID.randomUUID().toString();
    String toolCallId = UUID.randomUUID().toString();

    traces.recordModelPayload(
        runId,
        modelCallId,
        new GlassboxModels.ModelCallPayload(
            List.of(
                new GlassboxModels.PromptMessage(
                    "USER",
                    "analyst",
                    "{\"secret\": \"-----BEGIN RSA PRIVATE KEY-----\nMIIEowMsgSecret111",
                    64),
                new GlassboxModels.PromptMessage("ASSISTANT", "analyst", "第二条正常消息", 7)),
            List.of(
                new GlassboxModels.ToolSchemaView(
                    "leaky_tool",
                    "工具说明：-----BEGIN RSA PRIVATE KEY-----\nMIIEowToolSecret222",
                    "{}"),
                new GlassboxModels.ToolSchemaView("read_file", "读取授权文件", "{\"type\":\"object\"}")),
            "secret=AKIAIOSFODNN7EXAMPLE:db_suffix_resp",
            List.of(
                new GlassboxModels.ToolCallDecision(
                    "c1",
                    "leaky_tool",
                    "{\"pem\":\"-----BEGIN EC PRIVATE KEY-----\nMHcCCallSecret333\"}"),
                new GlassboxModels.ToolCallDecision("c2", "read_file", "{\"path\":\"sort.js\"}")),
            false,
            null,
            "password=***MASKED***_db_suffix_reason",
            true));

    var storedModel = traces.modelPayloadsByRun(runId).get(modelCallId);
    assertNotNull(storedModel, "含未闭合 PEM 的模型快照必须能从 H2 完整读回");
    assertEquals(2, storedModel.requestTools().size(), "工具列表不得因未闭合 PEM 吞掉 JSON 数组闭合符而丢失条目");
    assertEquals("read_file", storedModel.requestTools().get(1).name());
    assertEquals("读取授权文件", storedModel.requestTools().get(1).description());
    assertFalse(storedModel.requestTools().get(0).description().contains("MIIEowToolSecret222"));
    assertFalse(storedModel.requestTools().get(0).description().contains("PRIVATE KEY"));
    assertTrue(storedModel.requestTools().get(0).description().contains("***MASKED-PRIVATE-KEY***"));

    assertEquals(2, storedModel.requestMessages().size(), "消息列表必须完整读回");
    assertFalse(storedModel.requestMessages().get(0).content().contains("MIIEowMsgSecret111"));
    assertEquals("第二条正常消息", storedModel.requestMessages().get(1).content());

    assertEquals(2, storedModel.responseToolCalls().size(), "工具决策列表必须完整读回");
    assertFalse(storedModel.responseToolCalls().get(0).argumentsJson().contains("MHcCCallSecret333"));
    assertEquals("read_file", storedModel.responseToolCalls().get(1).name());

    assertFalse(storedModel.responseContent().contains("db_suffix_resp"));
    assertFalse(storedModel.responseContent().contains("AKIAIOSFODNN7EXAMPLE"));
    assertFalse(storedModel.reasoningContent().contains("db_suffix_reason"));

    // 工具安检列表含未闭合 PEM 同样必须在 H2 写读回后保留全部条目与脱敏结果。
    traces.recordToolTrace(
        runId,
        toolCallId,
        new GlassboxModels.ToolCallTrace(
            "{\"secret\":\"-----BEGIN RSA PRIVATE KEY-----\nMIIEowArgs444\"}",
            List.of(
                new GlassboxModels.GatewayCheck(
                    "ARGUMENT_SCHEMA",
                    "参数校验",
                    "PASS",
                    "说明：-----BEGIN RSA PRIVATE KEY-----\nMIIEowCheck555"),
                new GlassboxModels.GatewayCheck("PATH_WHITELIST", "路径白名单", "DENY", "越权路径")),
            "ok",
            false));
    var storedTool = traces.toolTracesByRun(runId).get(toolCallId);
    assertNotNull(storedTool);
    assertEquals(2, storedTool.gatewayChecks().size(), "安检列表不得因未闭合 PEM 破坏 JSON 数组");
    assertFalse(storedTool.gatewayChecks().get(0).detail().contains("MIIEowCheck555"));
    assertEquals("DENY", storedTool.gatewayChecks().get(1).status());
    assertFalse(storedTool.inputArgs().contains("MIIEowArgs444"));
  }

  /**
   * 反例：工具参数/Schema 本身是 JSON 字符串，未闭合 PEM 会吞掉内层闭合引号、相邻正常字段与闭合花括号，
   * 落库读回后无法再解析。必须在叶子层脱敏，保证脱敏结果仍是合法 JSON。
   */
  @Test
  void innerJsonLeafMaskingKeepsArgumentsAndSchemaParseableAfterDbRoundTrip() throws Exception {
    String runId = UUID.randomUUID().toString();
    String modelCallId = UUID.randomUUID().toString();
    String toolCallId = UUID.randomUUID().toString();
    String argumentJson =
        "{\"pem\":\"-----BEGIN EC PRIVATE KEY-----\\nMHcCCallSecret333\",\"path\":\"sort.js\"}";
    String schemaJson =
        "{\"type\":\"object\",\"properties\":{\"pem\":{\"default\":\"-----BEGIN RSA PRIVATE KEY-----\\nMIIESchemaSecret444\"},\"path\":{\"const\":\"sort.js\"}},\"examples\":[\"-----BEGIN OPENSSH PRIVATE KEY-----\\nAAAANestedSecret555\",\"sort.js\"]}";

    traces.recordModelPayload(
        runId,
        modelCallId,
        new GlassboxModels.ModelCallPayload(
            List.of(),
            List.of(new GlassboxModels.ToolSchemaView("sign", "签名工具", schemaJson)),
            "ok",
            List.of(new GlassboxModels.ToolCallDecision("c1", "sign", argumentJson)),
            false,
            null));
    traces.recordToolTrace(
        runId,
        toolCallId,
        new GlassboxModels.ToolCallTrace(
            argumentJson, List.of(), "ok", false));

    var json = new ObjectMapper();

    var storedModel = traces.modelPayloadsByRun(runId).get(modelCallId);
    assertNotNull(storedModel, "含未闭合 PEM 的工具载荷必须能从 H2 完整读回");
    String storedArgs = storedModel.responseToolCalls().getFirst().argumentsJson();
    String storedSchema = storedModel.requestTools().getFirst().parametersJson();

    var argsNode = json.readTree(storedArgs);
    assertEquals("sort.js", argsNode.get("path").asText(), "相邻正常字段必须完整保留");
    assertEquals("***MASKED-PRIVATE-KEY***", argsNode.get("pem").asText());
    assertFalse(storedArgs.contains("MHcCCallSecret333"));

    var schemaNode = json.readTree(storedSchema);
    assertEquals("sort.js", schemaNode.at("/properties/path/const").asText(), "嵌套对象字段必须完整保留");
    assertEquals(
        "sort.js", schemaNode.get("examples").get(1).asText(), "数组内的普通条目必须完整保留");
    assertFalse(storedSchema.contains("MIIESchemaSecret444"));
    assertFalse(storedSchema.contains("AAAANestedSecret555"));

    var storedTool = traces.toolTracesByRun(runId).get(toolCallId);
    assertNotNull(storedTool, "工具入参必须能从 H2 完整读回");
    var inputNode = json.readTree(storedTool.inputArgs());
    assertEquals("sort.js", inputNode.get("path").asText());
    assertEquals("***MASKED-PRIVATE-KEY***", inputNode.get("pem").asText());
    assertFalse(storedTool.inputArgs().contains("MHcCCallSecret333"));
  }

  /** 反例：无专项前缀的敏感键值（空格/逗号/转义引号）在叶子拆分后不得漏网。 */
  @Test
  void sensitiveKeysWithoutKnownPrefixAreStillMaskedAndJsonStaysReadable() throws Exception {
    String runId = UUID.randomUUID().toString();
    String modelCallId = UUID.randomUUID().toString();
    String toolCallId = UUID.randomUUID().toString();
    String payload =
        "{\"password\":\"my secret, pass \\\"123\\\"\",\"apiKey\":\"plain_opaque_999\",\"path\":\"a.js\"}";

    traces.recordModelPayload(
        runId,
        modelCallId,
        new GlassboxModels.ModelCallPayload(
            List.of(),
            List.of(),
            "ok",
            List.of(new GlassboxModels.ToolCallDecision("c1", "write_file", payload)),
            false,
            null));
    traces.recordToolTrace(
        runId, toolCallId, new GlassboxModels.ToolCallTrace(payload, List.of(), "ok", false));

    var json = new ObjectMapper();
    var storedModel = traces.modelPayloadsByRun(runId).get(modelCallId);
    var callNode = json.readTree(storedModel.responseToolCalls().getFirst().argumentsJson());
    assertEquals("***MASKED***", callNode.get("password").asText());
    assertEquals("***MASKED***", callNode.get("apiKey").asText());
    assertEquals("a.js", callNode.get("path").asText());
    String stored = storedModel.responseToolCalls().getFirst().argumentsJson();
    assertFalse(stored.contains("my secret"));
    assertFalse(stored.contains("plain_opaque_999"));

    var toolNode = json.readTree(traces.toolTracesByRun(runId).get(toolCallId).inputArgs());
    assertEquals("***MASKED***", toolNode.get("password").asText());
    assertEquals("***MASKED***", toolNode.get("apiKey").asText());
    assertEquals("a.js", toolNode.get("path").asText());
  }

  /** 反例：敏感键名下「专项密钥/占位符前缀 + 明文后缀」混合值不得因部分命中专项正则而放行明文尾部。 */
  @Test
  void sensitiveKeysWithMixedTokenAndPlainSuffixAreFullyMasked() throws Exception {
    String runId = UUID.randomUUID().toString();
    String modelCallId = UUID.randomUUID().toString();
    String toolCallId = UUID.randomUUID().toString();
    String payload =
        "{\"secret\":\"AKIAIOSFODNN7EXAMPLE:plain_tail_secret\","
            + "\"password\":\"***MASKED***_plain_suffix\","
            + "\"apiKey\":\"sk-123456789012\","
            + "\"path\":\"safe.js\"}";

    traces.recordModelPayload(
        runId,
        modelCallId,
        new GlassboxModels.ModelCallPayload(
            List.of(),
            List.of(),
            "ok",
            List.of(new GlassboxModels.ToolCallDecision("c1", "write_file", payload)),
            false,
            null));
    traces.recordToolTrace(
        runId, toolCallId, new GlassboxModels.ToolCallTrace(payload, List.of(), "ok", false));

    var json = new ObjectMapper();
    var callNode =
        json.readTree(
            traces.modelPayloadsByRun(runId).get(modelCallId).responseToolCalls().getFirst().argumentsJson());
    assertEquals("***MASKED***", callNode.get("secret").asText());
    assertEquals("***MASKED***", callNode.get("password").asText());
    assertEquals("sk-***MASKED***", callNode.get("apiKey").asText());
    assertEquals("safe.js", callNode.get("path").asText());

    var toolArgs = traces.toolTracesByRun(runId).get(toolCallId).inputArgs();
    var toolNode = json.readTree(toolArgs);
    assertEquals("***MASKED***", toolNode.get("secret").asText());
    assertEquals("***MASKED***", toolNode.get("password").asText());
    assertFalse(toolArgs.contains("plain_tail_secret"));
    assertFalse(toolArgs.contains("plain_suffix"));
  }

  /** 反例：非法 JSON 文本 + 未闭合 PEM 时不得抛异常，敏感片段仍被脱敏，外层列表依然可反序列化。 */
  @Test
  void invalidJsonArgumentsFallBackToWholeTextMaskingAndKeepOuterListReadable() {
    String runId = UUID.randomUUID().toString();
    String modelCallId = UUID.randomUUID().toString();
    String toolCallId = UUID.randomUUID().toString();
    String broken = "path=sort.js, pem=-----BEGIN RSA PRIVATE KEY-----\nMIIEBrokenSecret666";

    traces.recordModelPayload(
        runId,
        modelCallId,
        new GlassboxModels.ModelCallPayload(
            List.of(),
            List.of(),
            "ok",
            List.of(
                new GlassboxModels.ToolCallDecision("c1", "sign", broken),
                new GlassboxModels.ToolCallDecision("c2", "read_file", "{\"path\":\"sort.js\"}")),
            false,
            null));
    traces.recordToolTrace(
        runId, toolCallId, new GlassboxModels.ToolCallTrace(broken, List.of(), "ok", false));

    var storedModel = traces.modelPayloadsByRun(runId).get(modelCallId);
    assertNotNull(storedModel, "非法 JSON 入参也不得让整条载荷读不回来");
    assertEquals(2, storedModel.responseToolCalls().size(), "外层工具调用列表必须完整读回");
    assertEquals("read_file", storedModel.responseToolCalls().get(1).name());
    String masked = storedModel.responseToolCalls().getFirst().argumentsJson();
    assertFalse(masked.contains("MIIEBrokenSecret666"), "未闭合 PEM 必须被脱敏");
    assertTrue(masked.contains("***MASKED-PRIVATE-KEY***"));

    var storedTool = traces.toolTracesByRun(runId).get(toolCallId);
    assertNotNull(storedTool);
    assertFalse(storedTool.inputArgs().contains("MIIEBrokenSecret666"));
  }

  /**
   * 端到端回归（生产端 ControlledModel → 落库 GlassboxTraceStore → H2 读回 → Jackson 解析）：
   * 即使工具 Schema 或工具调用参数包含未闭合 PEM、敏感键值或超过 8,192 字符的超长字段，
   * 生产端也必须先走 JSON 叶子脱敏与树内限长，不能提前破坏 JSON 导致落库后无法反序列化。
   */
  @Test
  void controlledModelProductionToH2RoundTripKeepsToolSchemaAndArgsValidJson() throws Exception {
    String runId = UUID.randomUUID().toString();
    String modelCallId = UUID.randomUUID().toString();
    var captured = new java.util.concurrent.atomic.AtomicReference<GlassboxModels.ModelCallPayload>();
    dev.workbench.framework.agent.execution.AgentRuntime runtime =
        new dev.workbench.framework.agent.execution.AgentRuntime() {
          @Override
          public void beforeModel(dev.workbench.domain.ModelKind modelName) {}

          @Override
          public void modelResult(Integer input, Integer output) {}

          @Override
          public void modelTrace(GlassboxModels.ModelCallPayload value) {
            captured.set(value);
          }

          @Override
          public void assemblyResolved(
              dev.workbench.framework.agent.assembly.AgentAssemblySnapshot.Components components) {}

          @Override
          public UUID beforeTool(
              dev.workbench.domain.ToolKind tool, int version, String fingerprint) {
            return UUID.randomUUID();
          }

          @Override
          public void check() {}

          @Override
          public void event(dev.workbench.domain.EventType type, String message) {}

          @Override
          public void toolResult(String result) {}

          @Override
          public void toolRejected(String safeMessage) {}
        };

    String longBody = "L".repeat(12_000);
    var toolSchema =
        io.agentscope.core.model.ToolSchema.builder()
            .name("sign_file")
            .description("签名并写入文件")
            .parameters(
                java.util.Map.of(
                    "type",
                    "object",
                    "properties",
                    java.util.Map.of(
                        "pem",
                        java.util.Map.of(
                            "default", "-----BEGIN RSA PRIVATE KEY-----\nMIIEProdSchemaSecret"),
                        "notes",
                        java.util.Map.of("description", longBody),
                        "path",
                        java.util.Map.of("const", "src/sort.js"))))
            .build();
    var responseChunk =
        io.agentscope.core.model.ChatResponse.builder()
            .content(
                List.of(
                    io.agentscope.core.message.TextBlock.builder().text("调用工具").build(),
                    io.agentscope.core.message.ToolUseBlock.builder()
                        .id("call-prod-1")
                        .name("sign_file")
                        .input(
                            java.util.Map.of(
                                "pem",
                                "-----BEGIN EC PRIVATE KEY-----\nMHcCProdArgSecret",
                                "apiKey",
                                "sk-prod1234567890abcdef",
                                "content",
                                longBody,
                                "path",
                                "src/sort.js"))
                        .build()))
            .build();
    io.agentscope.core.model.Model stubDelegate =
        new io.agentscope.core.model.Model() {
          @Override
          public reactor.core.publisher.Flux<io.agentscope.core.model.ChatResponse> stream(
              List<io.agentscope.core.message.Msg> messages,
              List<io.agentscope.core.model.ToolSchema> tools,
              io.agentscope.core.model.GenerateOptions options) {
            return reactor.core.publisher.Flux.just(responseChunk);
          }

          @Override
          public String getModelName() {
            return "prod-stub";
          }

          @Override
          public int getContextWindowSize() {
            return 0;
          }
        };

    var controlled =
        new dev.workbench.infrastructure.agentscope.ControlledModel(
            stubDelegate,
            dev.workbench.domain.ModelMode.MOCK,
            runtime,
            new java.util.concurrent.atomic.AtomicReference<RuntimeException>());
    controlled.stream(List.of(), List.of(toolSchema), null).collectList().block();

    assertNotNull(captured.get(), "ControlledModel 必须产出玻璃盒载荷");
    assertTrue(captured.get().truncated(), "超过 8,192 字符的大字段应标记 truncated");
    traces.recordModelPayload(runId, modelCallId, captured.get());

    var stored = traces.modelPayloadsByRun(runId).get(modelCallId);
    assertNotNull(stored, "落库后必须能从 H2 完整读回");
    var mapper = new ObjectMapper();

    String storedSchema = stored.requestTools().getFirst().parametersJson();
    assertTrue(storedSchema.length() <= 8_192, "工具 Schema 应在生产端限长到 8,192 以内");
    var schemaNode = mapper.readTree(storedSchema);
    assertEquals("src/sort.js", schemaNode.at("/properties/path/const").asText(), "相邻字段必须完整保留");
    assertEquals("***MASKED-PRIVATE-KEY***", schemaNode.at("/properties/pem/default").asText());
    assertFalse(storedSchema.contains("MIIEProdSchemaSecret"));

    String storedArgs = stored.responseToolCalls().getFirst().argumentsJson();
    assertTrue(storedArgs.length() <= 8_192, "工具参数应在生产端限长到 8,192 以内");
    var argsNode = mapper.readTree(storedArgs);
    assertEquals("src/sort.js", argsNode.get("path").asText(), "相邻路径字段必须完整保留");
    assertEquals("***MASKED-PRIVATE-KEY***", argsNode.get("pem").asText());
    assertFalse(storedArgs.contains("MHcCProdArgSecret"));
    assertFalse(storedArgs.contains("sk-prod1234567890abcdef"));
    assertTrue(argsNode.get("content").asText().endsWith("...[已截断]"), "超长文本叶子应被优雅截断而非破坏 JSON");
  }

  /**
   * 宽 JSON 端到端回归（对应独立评审 80 标签反例 + 宽数值数组）：
   * 80 个各 400 字符的标签及大规模纯数值数组，经 ControlledModel 生产端 → GlassboxTraceStore → H2 落库读回后，
   * 长度必须严格 <= 8,192（含截断标记本身）且仍可被 Jackson 正常解析，绝不退化为非法截断文本。
   */
  @Test
  void wideJsonWith80TagsAndNumericArrayStaysValidAndWithinLimitAfterH2RoundTrip() throws Exception {
    String runId = UUID.randomUUID().toString();
    String modelCallId = UUID.randomUUID().toString();
    var captured = new java.util.concurrent.atomic.AtomicReference<GlassboxModels.ModelCallPayload>();
    dev.workbench.framework.agent.execution.AgentRuntime runtime =
        new dev.workbench.framework.agent.execution.AgentRuntime() {
          @Override
          public void beforeModel(dev.workbench.domain.ModelKind modelName) {}

          @Override
          public void modelResult(Integer input, Integer output) {}

          @Override
          public void modelTrace(GlassboxModels.ModelCallPayload value) {
            captured.set(value);
          }

          @Override
          public void assemblyResolved(
              dev.workbench.framework.agent.assembly.AgentAssemblySnapshot.Components components) {}

          @Override
          public UUID beforeTool(
              dev.workbench.domain.ToolKind tool, int version, String fingerprint) {
            return UUID.randomUUID();
          }

          @Override
          public void check() {}

          @Override
          public void event(dev.workbench.domain.EventType type, String message) {}

          @Override
          public void toolResult(String result) {}

          @Override
          public void toolRejected(String safeMessage) {}
        };

    var wideTags = new java.util.ArrayList<String>();
    for (int i = 0; i < 80; i++) {
      wideTags.add("tag-" + i + "-" + "x".repeat(390));
    }
    var wideNumbers = new java.util.ArrayList<Integer>();
    for (int i = 0; i < 2500; i++) {
      wideNumbers.add(100_000 + i);
    }
    var toolSchema =
        io.agentscope.core.model.ToolSchema.builder()
            .name("bulk_tag")
            .description("批量标签工具")
            .parameters(
                java.util.Map.of(
                    "type",
                    "object",
                    "properties",
                    java.util.Map.of(
                        "tags", java.util.Map.of("enum", wideTags),
                        "ids", java.util.Map.of("examples", wideNumbers))))
            .build();
    var responseChunk =
        io.agentscope.core.model.ChatResponse.builder()
            .content(
                List.of(
                    io.agentscope.core.message.ToolUseBlock.builder()
                        .id("call-wide-1")
                        .name("bulk_tag")
                        .input(java.util.Map.of("tags", wideTags, "ids", wideNumbers))
                        .build()))
            .build();
    io.agentscope.core.model.Model stubDelegate =
        new io.agentscope.core.model.Model() {
          @Override
          public reactor.core.publisher.Flux<io.agentscope.core.model.ChatResponse> stream(
              List<io.agentscope.core.message.Msg> messages,
              List<io.agentscope.core.model.ToolSchema> tools,
              io.agentscope.core.model.GenerateOptions options) {
            return reactor.core.publisher.Flux.just(responseChunk);
          }

          @Override
          public String getModelName() {
            return "wide-stub";
          }

          @Override
          public int getContextWindowSize() {
            return 0;
          }
        };

    var controlled =
        new dev.workbench.infrastructure.agentscope.ControlledModel(
            stubDelegate,
            dev.workbench.domain.ModelMode.MOCK,
            runtime,
            new java.util.concurrent.atomic.AtomicReference<RuntimeException>());
    controlled.stream(List.of(), List.of(toolSchema), null).collectList().block();

    assertNotNull(captured.get());
    assertTrue(captured.get().truncated(), "宽 JSON 裁剪后必须标记 truncated");
    traces.recordModelPayload(runId, modelCallId, captured.get());

    var stored = traces.modelPayloadsByRun(runId).get(modelCallId);
    assertNotNull(stored);
    var mapper = new ObjectMapper();

    String storedSchema = stored.requestTools().getFirst().parametersJson();
    assertTrue(
        storedSchema.length() <= 8_192,
        "宽 Schema 截断后必须严格 <= 8,192，实际：" + storedSchema.length());
    assertTrue(mapper.readTree(storedSchema).isObject(), "宽 Schema 从 H2 读回后仍必须是合法 JSON");

    String storedArgs = stored.responseToolCalls().getFirst().argumentsJson();
    assertTrue(
        storedArgs.length() <= 8_192,
        "宽参数截断后必须严格 <= 8,192，实际：" + storedArgs.length());
    assertTrue(mapper.readTree(storedArgs).isObject(), "宽参数从 H2 读回后仍必须是合法 JSON");
  }

  /**
   * 超大字段名（60,000 字符，触发 Jackson 默认 50,000 字段名读约束 StreamConstraintsException）的 H2 闭环：
   * 必须直接落成预算内合法摘要信封，绝不能退回 8,201 字符的非法截断文本。
   */
  @Test
  void oversizedJsonKeyExceedingJacksonConstraintProducesValidEnvelopeAfterH2RoundTrip()
      throws Exception {
    String runId = UUID.randomUUID().toString();
    String modelCallId = UUID.randomUUID().toString();
    var captured = new java.util.concurrent.atomic.AtomicReference<GlassboxModels.ModelCallPayload>();
    dev.workbench.framework.agent.execution.AgentRuntime runtime =
        new dev.workbench.framework.agent.execution.AgentRuntime() {
          @Override
          public void beforeModel(dev.workbench.domain.ModelKind modelName) {}

          @Override
          public void modelResult(Integer input, Integer output) {}

          @Override
          public void modelTrace(GlassboxModels.ModelCallPayload value) {
            captured.set(value);
          }

          @Override
          public void assemblyResolved(
              dev.workbench.framework.agent.assembly.AgentAssemblySnapshot.Components components) {}

          @Override
          public UUID beforeTool(
              dev.workbench.domain.ToolKind tool, int version, String fingerprint) {
            return UUID.randomUUID();
          }

          @Override
          public void check() {}

          @Override
          public void event(dev.workbench.domain.EventType type, String message) {}

          @Override
          public void toolResult(String result) {}

          @Override
          public void toolRejected(String safeMessage) {}
        };

    String giantKey = "k".repeat(60_000);
    var toolSchema =
        io.agentscope.core.model.ToolSchema.builder()
            .name("giant_key_tool")
            .description("超长键名测试")
            .parameters(java.util.Map.of(giantKey, "secret-value"))
            .build();
    var responseChunk =
        io.agentscope.core.model.ChatResponse.builder()
            .content(
                List.of(
                    io.agentscope.core.message.ToolUseBlock.builder()
                        .id("call-giant-1")
                        .name("giant_key_tool")
                        .input(java.util.Map.of(giantKey, "secret-value"))
                        .build()))
            .build();
    io.agentscope.core.model.Model stubDelegate =
        new io.agentscope.core.model.Model() {
          @Override
          public reactor.core.publisher.Flux<io.agentscope.core.model.ChatResponse> stream(
              List<io.agentscope.core.message.Msg> messages,
              List<io.agentscope.core.model.ToolSchema> tools,
              io.agentscope.core.model.GenerateOptions options) {
            return reactor.core.publisher.Flux.just(responseChunk);
          }

          @Override
          public String getModelName() {
            return "giant-key-stub";
          }

          @Override
          public int getContextWindowSize() {
            return 0;
          }
        };

    var controlled =
        new dev.workbench.infrastructure.agentscope.ControlledModel(
            stubDelegate,
            dev.workbench.domain.ModelMode.MOCK,
            runtime,
            new java.util.concurrent.atomic.AtomicReference<RuntimeException>());
    controlled.stream(List.of(), List.of(toolSchema), null).collectList().block();

    assertNotNull(captured.get());
    assertTrue(captured.get().truncated());
    traces.recordModelPayload(runId, modelCallId, captured.get());

    var stored = traces.modelPayloadsByRun(runId).get(modelCallId);
    assertNotNull(stored);
    var mapper = new ObjectMapper();

    String storedSchema = stored.requestTools().getFirst().parametersJson();
    assertTrue(storedSchema.length() <= 8_192, "60,000 字键名 Schema 必须在限长内，实际：" + storedSchema.length());
    var schemaNode = mapper.readTree(storedSchema);
    assertTrue(schemaNode.get("_truncated").asBoolean(), "应输出合法截断摘要信封");
    assertFalse(storedSchema.contains("secret-value"), "摘要信封不得回显原始字段值");

    String storedArgs = stored.responseToolCalls().getFirst().argumentsJson();
    assertTrue(storedArgs.length() <= 8_192, "60,000 字键名参数必须在限长内，实际：" + storedArgs.length());
    var argsNode = mapper.readTree(storedArgs);
    assertTrue(argsNode.get("_truncated").asBoolean(), "应输出合法截断摘要信封");
    assertFalse(storedArgs.contains("secret-value"), "摘要信封不得回显原始字段值");
  }
}
