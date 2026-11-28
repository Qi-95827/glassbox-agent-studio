package dev.workbench.orchestration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.workbench.domain.AgentRole;
import dev.workbench.domain.ModelKind;
import dev.workbench.domain.ModelMode;
import dev.workbench.framework.agent.assembly.AgentAssemblySnapshot;
import dev.workbench.framework.agent.definition.AgentDefinitionRef;
import dev.workbench.framework.control.RunControl;
import dev.workbench.framework.event.RunStreamTransient;
import dev.workbench.framework.trace.GlassboxModels;
import dev.workbench.infrastructure.agentscope.ControlledModel;
import dev.workbench.store.AgentAssemblyRecordService;
import dev.workbench.store.GlassboxTraceStore;
import dev.workbench.store.RunEvidenceRecordService;
import dev.workbench.store.RunStateStore;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import reactor.core.publisher.Flux;

/**
 * 实时 SSE 的跨帧脱敏：任意分帧方式下，前端拼接出来的正文与思考链都不含明文敏感串。
 *
 * <p>走真实生产路径：{@link ControlledAgentRuntime} 逐帧接收净增量，经 {@link
 * dev.workbench.framework.trace.StreamingMasker} 处理后发布 {@link RunStreamTransient}；这里用真实
 * {@link ApplicationEventPublisher} 订阅并拼接，等价于浏览器侧把帧拼起来的最终结果。
 */
class StreamingSseMaskingTest {
  private static final String KEY = "sk-live-9f8e7d6c5b4a3210";
  private static final String JWT = "eyJhbGciOiJIUzI1NiJ9.cGF5bG9hZA.c2lnbmF0dXJl";
  private static final String PASSWORD = "password=Hunter2-Super-Secret";
  private static final String PEM =
      "-----BEGIN RSA PRIVATE KEY-----\nMIIEowIBAAKCAQEA1234567890\n-----END RSA PRIVATE KEY-----";

  private static final String BODY =
      "已核对：key=" + KEY + "，请求头 Bearer " + JWT + "，" + PASSWORD + "。其余正常，可以继续。";
  private static final String REASONING =
      "先检查凭据：token " + JWT + "，再确认 " + PASSWORD + " 不外泄。";

  @Test
  void plainTextStillStreamsBeforeCompletion() {
    CapturingPublisher publisher = new CapturingPublisher();
    ControlledAgentRuntime runtime = runtime(publisher);
    String plain = "这是一段普通的中文流式输出，不包含任何敏感形态。".repeat(8);

    for (char c : plain.toCharArray()) runtime.modelDelta(String.valueOf(c));

    // 无敏感特征时不能退化成全量缓冲：除尾部安全滞留窗口外，绝大多数文本必须已经发出。
    String streamed = publisher.joined("MODEL_DELTA");
    assertTrue(streamed.length() >= plain.length() - 80, "普通文本应即时吐出，实际只有 " + streamed.length());
    assertTrue(streamed.length() > 0, "不能在流未结束前一个字都不发");
    runtime.modelResult(null, null);
    assertEquals(plain, publisher.joined("MODEL_DELTA"));
  }

  @Test
  void oneCharacterFramesNeverLeakAcrossTheBoundary() {
    assertNoLeak(splitEveryCharacter(BODY), splitEveryCharacter(REASONING));
  }

  @Test
  void splitsInsideTheKeywordBoundaryNeverLeak() {
    // 在 `sk-` 正中间、`Bearer ` 之后、`password=` 之后切开：单帧正则都命中不了的分帧法。
    List<String> content = new ArrayList<>();
    int cut1 = BODY.indexOf("sk-") + 2;
    int cut2 = BODY.indexOf("Bearer ") + 7;
    int cut3 = BODY.indexOf("password=") + 9;
    content.add(BODY.substring(0, cut1));
    content.add(BODY.substring(cut1, cut2));
    content.add(BODY.substring(cut2, cut3));
    content.add(BODY.substring(cut3) + PEM);
    assertNoLeak(content, List.of(REASONING.substring(0, 3), REASONING.substring(3)));
  }

  @Test
  void randomSmallChunksNeverLeak() {
    Random random = new Random(20261127L);
    assertNoLeak(splitRandomly(BODY + "\n" + PEM, random), splitRandomly(REASONING, random));
  }

  @Test
  void quotedJsonAndGithubPatTokensNeverLeakAcrossFrames() {
    String text =
        "JSON 凭据：{\"password\": \"super-secret-998877\", 'token': 'abc987654'}，"
            + "令牌 github_pat_11AA22BB33CC44DD55EE66 结束。";
    for (List<String> frames :
        List.of(
            splitEveryCharacter(text),
            List.of(text.substring(0, text.indexOf("super-secret") + 3), text.substring(text.indexOf("super-secret") + 3)),
            List.of(text.substring(0, text.indexOf("github_pat_") + 5), text.substring(text.indexOf("github_pat_") + 5)))) {
      CapturingPublisher publisher = new CapturingPublisher();
      ControlledAgentRuntime runtime = runtime(publisher);
      for (String frame : frames) {
        runtime.modelDelta(frame);
        runtime.modelReasoningDelta(frame);
      }
      runtime.modelResult(null, null);
      String content = publisher.joined("MODEL_DELTA");
      String reasoning = publisher.joined("MODEL_REASONING_DELTA");
      assertFalse(content.contains("super-secret-998877"), "双引号 JSON 口令泄露：" + content);
      assertFalse(content.contains("abc987654"), "单引号 KV 令牌泄露：" + content);
      assertFalse(content.contains("github_pat_11AA22BB33CC44DD55EE66"), "GitHub PAT 泄露：" + content);
      assertFalse(reasoning.contains("super-secret-998877"), "思考链泄露口令：" + reasoning);
      assertTrue(content.startsWith("JSON 凭据："), content);
      assertTrue(content.contains("结束。"), content);
    }
  }

  @Test
  void abortedStreamMasksUnclosedPrivateKeyAndPartialCredentials() {
    CapturingPublisher keyPublisher = new CapturingPublisher();
    ControlledAgentRuntime keyRuntime = runtime(keyPublisher);
    keyRuntime.modelDelta("已准备私钥：-----BEGIN RSA PRIVATE KEY-----\nMIIEowIBAAKCAQEA1234567890");
    keyRuntime.modelFailed();
    String keyText = keyPublisher.joined("MODEL_DELTA");
    assertFalse(keyText.contains("MIIEowIBAAKCAQEA1234567890"), "中断时未闭合私钥正文泄露：" + keyText);
    assertFalse(keyText.contains("BEGIN RSA PRIVATE KEY"), "私钥头也不应原样保留：" + keyText);
    assertTrue(keyText.startsWith("已准备私钥："), keyText);

    CapturingPublisher jsonPublisher = new CapturingPublisher();
    ControlledAgentRuntime jsonRuntime = runtime(jsonPublisher);
    jsonRuntime.modelDelta("配置：{\"api_key\": \"sk-partial");
    jsonRuntime.modelFailed();
    String jsonText = jsonPublisher.joined("MODEL_DELTA");
    assertFalse(jsonText.contains("sk-partial"), "中断时半截 api_key 泄露：" + jsonText);
    assertTrue(jsonText.contains("***MASKED***"), jsonText);
  }

  @Test
  void ordinaryJsonStreamsUnchanged() {
    String json = "{\"status\":\"ok\",\"url\":\"http://localhost:8088/runs\"}";
    CapturingPublisher publisher = new CapturingPublisher();
    ControlledAgentRuntime runtime = runtime(publisher);
    for (char c : json.toCharArray()) runtime.modelDelta(String.valueOf(c));
    runtime.modelResult(null, null);
    assertEquals(json, publisher.joined("MODEL_DELTA"));
  }

  @Test
  void finalFlushDoesNotCorruptOrdinaryWordsOrDoubleMaskSecrets() {
    String text = "本次统计 token: 100，覆盖 East Asia 区域，核对 key=" + KEY + " 已完成。";
    CapturingPublisher publisher = new CapturingPublisher();
    ControlledAgentRuntime runtime = runtime(publisher);
    for (char c : text.toCharArray()) runtime.modelDelta(String.valueOf(c));
    runtime.modelResult(null, null);
    String content = publisher.joined("MODEL_DELTA");
    assertFalse(content.contains(KEY), content);
    assertFalse(content.contains("***MASKED******MASKED***"), "不得对已脱敏前缀重复叠加掩码：" + content);
    assertTrue(content.contains("East Asia"), "不得因 (?i) 误伤普通英文单词 Asia：" + content);
    assertTrue(content.contains("token: 100"), "不得误伤非末尾普通数字统计：" + content);
  }

  @Test
  void multiLineFormattedCredentialsNeverLeakAcrossFramesAndMatchWholeMask() {
    String prefix = "这是一段超过流式保留窗口长度的普通前置说明文本，用来确保切点会真正落在后续多行凭据的键与换行边界上。".repeat(3);
    String multiLine =
        prefix
            + "\n{\n  \"password\"\n  :\n  \"MultiLine-Secret-9988\",\n"
            + "  'token'\r\n  =\r\n  'MultiToken-7766'\n}\n"
            + "Authorization: Bearer\n  "
            + JWT
            + "\n收尾正常文本。";
    String expected = dev.workbench.framework.trace.SensitiveDataMasker.mask(multiLine);
    int afterPasswordNewline = multiLine.indexOf("\"password\"\n") + "\"password\"\n".length();
    int afterColonNewline = multiLine.indexOf(":\n  ") + ":\n  ".length();
    int afterBearerNewline = multiLine.indexOf("Bearer\n") + "Bearer\n".length();

    for (List<String> frames :
        List.of(
            splitEveryCharacter(multiLine),
            List.of(multiLine.substring(0, afterPasswordNewline), multiLine.substring(afterPasswordNewline)),
            List.of(multiLine.substring(0, afterColonNewline), multiLine.substring(afterColonNewline)),
            List.of(multiLine.substring(0, afterBearerNewline), multiLine.substring(afterBearerNewline)),
            splitRandomly(multiLine, new Random(20261128L)))) {
      CapturingPublisher publisher = new CapturingPublisher();
      ControlledAgentRuntime runtime = runtime(publisher);
      for (String frame : frames) {
        runtime.modelDelta(frame);
        runtime.modelReasoningDelta(frame);
      }
      runtime.modelResult(null, null);
      String content = publisher.joined("MODEL_DELTA");
      String reasoning = publisher.joined("MODEL_REASONING_DELTA");
      assertFalse(content.contains("MultiLine-Secret-9988"), "多行双引号口令泄露：" + content);
      assertFalse(content.contains("MultiToken-7766"), "多行单引号令牌泄露：" + content);
      assertFalse(content.contains(JWT), "跨行 Bearer JWT 泄露：" + content);
      assertEquals(expected, content, "任意分帧拼接结果必须与整段脱敏完全一致");
      assertEquals(expected, reasoning, "思考链任意分帧拼接结果必须与整段脱敏完全一致");
    }
  }

  @Test
  void trailingBareHostPortUrlAndTokenCountAreNotCorruptedWhileAbortedSecretsAreMasked() {
    // 正常完成时，末尾裸主机+端口 URL 与末尾普通 token 计数均不得被误伤。
    for (String normalTail :
        List.of("服务已启动于 http://localhost:8088", "本次统计 token: 100")) {
      CapturingPublisher publisher = new CapturingPublisher();
      ControlledAgentRuntime runtime = runtime(publisher);
      for (char c : normalTail.toCharArray()) runtime.modelDelta(String.valueOf(c));
      runtime.modelResult(null, null);
      assertEquals(normalTail, publisher.joined("MODEL_DELTA"));
    }

    // 即使在流异常中断时，普通主机+端口 URL 也不应被当成缺 @ 凭据误伤；真正的半截 URL 凭据与多行半截口令必须掩码。
    CapturingPublisher barePortAborted = new CapturingPublisher();
    ControlledAgentRuntime barePortRuntime = runtime(barePortAborted);
    barePortRuntime.modelDelta("当前监听 http://localhost:8088");
    barePortRuntime.modelFailed();
    assertEquals("当前监听 http://localhost:8088", barePortAborted.joined("MODEL_DELTA"));

    CapturingPublisher urlCredAborted = new CapturingPublisher();
    ControlledAgentRuntime urlCredRuntime = runtime(urlCredAborted);
    urlCredRuntime.modelDelta("数据库连接 postgres://app:pa55word");
    urlCredRuntime.modelFailed();
    assertFalse(urlCredAborted.joined("MODEL_DELTA").contains("pa55word"));
    assertTrue(urlCredAborted.joined("MODEL_DELTA").contains("***MASKED***"));

    CapturingPublisher multiLineAborted = new CapturingPublisher();
    ControlledAgentRuntime multiLineRuntime = runtime(multiLineAborted);
    multiLineRuntime.modelDelta("配置：\n{\"password\"\n  :\n  \"halfSec");
    multiLineRuntime.modelFailed();
    assertFalse(multiLineAborted.joined("MODEL_DELTA").contains("halfSec"));
    assertTrue(multiLineAborted.joined("MODEL_DELTA").contains("***MASKED***"));
  }

  @Test
  void opaqueBearerInAuthorizationHeaderNeverLeaksAcrossAnySplitPoint() {
    String opaque = "opaque_secret_99887766aabb";
    String prefix = "这是一段超过流式保留窗口长度的普通前置文本，保证切点会扫过整个 Authorization 与 Bearer 令牌区域。".repeat(3);
    String suffix = "。后续还有超过保留窗口长度的普通说明文本，确保凭据段会在流结束前触发发射而非拖到最后。".repeat(3);
    List<String> payloads =
        List.of(
            prefix + "Authorization: Bearer " + opaque + suffix,
            prefix + "{\"Authorization\": \"Bearer " + opaque + "\"}" + suffix,
            prefix + "Authorization:\n  Bearer\n  " + opaque + suffix);

    for (String text : payloads) {
      String expected = dev.workbench.framework.trace.SensitiveDataMasker.mask(text);
      int afterBearer = text.indexOf("Bearer") + "Bearer".length();
      int afterOpaqueHalf = text.indexOf(opaque) + 6;
      for (List<String> frames :
          List.of(
              splitEveryCharacter(text),
              List.of(text.substring(0, afterBearer), text.substring(afterBearer)),
              List.of(text.substring(0, afterBearer + 1), text.substring(afterBearer + 1)),
              List.of(text.substring(0, afterOpaqueHalf), text.substring(afterOpaqueHalf)),
              splitRandomly(text, new Random(20261129L)))) {
        CapturingPublisher publisher = new CapturingPublisher();
        ControlledAgentRuntime runtime = runtime(publisher);
        for (String frame : frames) {
          runtime.modelDelta(frame);
          runtime.modelReasoningDelta(frame);
        }
        runtime.modelFinishReason("stop");
        runtime.modelResult(null, null);
        String content = publisher.joined("MODEL_DELTA");
        String reasoning = publisher.joined("MODEL_REASONING_DELTA");
        assertFalse(content.contains(opaque), "普通不透明 Bearer 令牌在正文流泄露：" + content);
        assertFalse(reasoning.contains(opaque), "普通不透明 Bearer 令牌在思考链泄露：" + reasoning);
        assertEquals(expected, content, "Authorization + 不透明 Bearer 任意分帧拼接必须与整段脱敏逐字一致");
        assertEquals(expected, reasoning, "思考链 Authorization + 不透明 Bearer 必须与整段脱敏逐字一致");
      }
    }
  }

  @Test
  void controlledModelLengthLimitCompletionUsesTruncatedFinalMaskWhileNaturalStopPreservesPlainTail() {
    // 1. 输出限长结束（length / MAX_TOKENS）即使走 onComplete，也必须对正文与思考链及持久化快照启用截断兜底。
    for (String limitReason : List.of("length", "MAX_TOKENS")) {
      CapturingPublisher publisher = new CapturingPublisher();
      var capturedPayload = new AtomicReference<GlassboxModels.ModelCallPayload>();
      ControlledAgentRuntime runtime = runtimeWithTrace(publisher, capturedPayload);
      Model delegate =
          stubModel(
              Flux.just(
                  ChatResponse.builder()
                      .content(
                          List.of(
                              TextBlock.builder().text("请求头 Authorization: Bearer ").build(),
                              ThinkingBlock.builder().thinking("配置：{\"api_key\": \"").build()))
                      .build(),
                  ChatResponse.builder()
                      .content(
                          List.of(
                              TextBlock.builder().text("opaq").build(),
                              ThinkingBlock.builder().thinking("sk-half").build()))
                      .usage(ChatUsage.builder().inputTokens(10).outputTokens(5).build())
                      .finishReason(limitReason)
                      .build()));
      var controlled =
          new ControlledModel(delegate, ModelMode.MOCK, runtime, new AtomicReference<>());
      controlled.stream(List.of(), List.of(), GenerateOptions.builder().build()).collectList().block();

      String content = publisher.joined("MODEL_DELTA");
      String reasoning = publisher.joined("MODEL_REASONING_DELTA");
      assertFalse(content.contains("opaq"), "限长结束(" + limitReason + ")正文半截不透明 Bearer 泄露：" + content);
      assertTrue(content.contains("***MASKED***"), content);
      assertFalse(reasoning.contains("sk-half"), "限长结束(" + limitReason + ")思考链半截 api_key 泄露：" + reasoning);
      assertTrue(reasoning.contains("***MASKED***"), reasoning);

      var payload = capturedPayload.get();
      assertNotNull(payload, "模型快照必须已记录");
      assertFalse(payload.responseContent().contains("opaq"), "快照正文同样不得残留半截凭据：" + payload.responseContent());
      assertFalse(payload.reasoningContent().contains("sk-half"), "快照思考链同样不得残留半截凭据：" + payload.reasoningContent());
    }

    // 2. 自然结束（finishReason=stop）：末尾裸主机+端口 URL 与普通 token 计数必须原样保留。
    CapturingPublisher naturalPublisher = new CapturingPublisher();
    var naturalPayload = new AtomicReference<GlassboxModels.ModelCallPayload>();
    ControlledAgentRuntime naturalRuntime = runtimeWithTrace(naturalPublisher, naturalPayload);
    Model naturalDelegate =
        stubModel(
            Flux.just(
                ChatResponse.builder()
                    .content(
                        List.of(
                            TextBlock.builder().text("服务已启动于 http://localhost:8088").build(),
                            ThinkingBlock.builder().thinking("本次统计 token: 100").build()))
                    .usage(ChatUsage.builder().inputTokens(10).outputTokens(5).build())
                    .finishReason("stop")
                    .build()));
    var naturalControlled =
        new ControlledModel(naturalDelegate, ModelMode.MOCK, naturalRuntime, new AtomicReference<>());
    naturalControlled
        .stream(List.of(), List.of(), GenerateOptions.builder().build())
        .collectList()
        .block();

    assertEquals("服务已启动于 http://localhost:8088", naturalPublisher.joined("MODEL_DELTA"));
    assertEquals("本次统计 token: 100", naturalPublisher.joined("MODEL_REASONING_DELTA"));
    assertEquals("服务已启动于 http://localhost:8088", naturalPayload.get().responseContent());
    assertEquals("本次统计 token: 100", naturalPayload.get().reasoningContent());
  }

  @Test
  void controlledModelErrorAndCancellationBothFlushViaTruncatedFinalMask() {
    // 1. 失败中断（onError）
    CapturingPublisher errorPublisher = new CapturingPublisher();
    ControlledAgentRuntime errorRuntime = runtimeWithTrace(errorPublisher, new AtomicReference<>());
    Model errorDelegate =
        stubModel(
            Flux.concat(
                Flux.just(
                    ChatResponse.builder()
                        .content(
                            List.of(
                                TextBlock.builder().text("Authorization: Bearer errPart").build(),
                                ThinkingBlock.builder()
                                    .thinking("连接串 postgres://app:errPass")
                                    .build()))
                        .build()),
                Flux.error(new IllegalStateException("upstream reset"))));
    var errorControlled =
        new ControlledModel(errorDelegate, ModelMode.MOCK, errorRuntime, new AtomicReference<>());
    assertThrows(
        IllegalStateException.class,
        () ->
            errorControlled
                .stream(List.of(), List.of(), GenerateOptions.builder().build())
                .collectList()
                .block());
    assertFalse(errorPublisher.joined("MODEL_DELTA").contains("errPart"));
    assertTrue(errorPublisher.joined("MODEL_DELTA").contains("***MASKED***"));
    assertFalse(errorPublisher.joined("MODEL_REASONING_DELTA").contains("errPass"));
    assertTrue(errorPublisher.joined("MODEL_REASONING_DELTA").contains("***MASKED***"));

    // 2. 下游取消（doOnCancel via take(1)）
    CapturingPublisher cancelPublisher = new CapturingPublisher();
    ControlledAgentRuntime cancelRuntime = runtimeWithTrace(cancelPublisher, new AtomicReference<>());
    Model cancelDelegate =
        stubModel(
            Flux.just(
                ChatResponse.builder()
                    .content(
                        List.of(
                            TextBlock.builder().text("配置：\n{\"password\"\n:\n\"cancelSec").build(),
                            ThinkingBlock.builder().thinking("{\"api_key\": \"sk-cancel").build()))
                    .build(),
                ChatResponse.builder()
                    .content(List.of(TextBlock.builder().text("不会到达的第二帧").build()))
                    .build()));
    var cancelControlled =
        new ControlledModel(cancelDelegate, ModelMode.MOCK, cancelRuntime, new AtomicReference<>());
    cancelControlled
        .stream(List.of(), List.of(), GenerateOptions.builder().build())
        .take(1)
        .collectList()
        .block();
    assertFalse(cancelPublisher.joined("MODEL_DELTA").contains("cancelSec"));
    assertTrue(cancelPublisher.joined("MODEL_DELTA").contains("***MASKED***"));
    assertFalse(cancelPublisher.joined("MODEL_REASONING_DELTA").contains("sk-cancel"));
    assertTrue(cancelPublisher.joined("MODEL_REASONING_DELTA").contains("***MASKED***"));
  }

  @Test
  void unclosedPemInsideSensitiveKvFieldNeverLeaksAcrossAllCompletionPathsAndSnapshot() {
    String longPrefix = "普通前置说明文本用于跨越流式窗口边界。".repeat(4);

    // 1. 自然结束（finishReason=stop）：敏感字段内嵌未闭合 PEM，跨帧切分后正文、推理与快照均无私钥正文。
    CapturingPublisher stopPub = new CapturingPublisher();
    var stopPayload = new AtomicReference<GlassboxModels.ModelCallPayload>();
    ControlledAgentRuntime stopRuntime = runtimeWithTrace(stopPub, stopPayload);
    Model stopDelegate =
        stubModel(
            Flux.just(
                ChatResponse.builder()
                    .content(
                        List.of(
                            TextBlock.builder()
                                .text(longPrefix + "{\"secret\": \"-----BEGIN RSA ")
                                .build(),
                            ThinkingBlock.builder()
                                .thinking(longPrefix + "password=-----BEGIN EC ")
                                .build()))
                    .build(),
                ChatResponse.builder()
                    .content(
                        List.of(
                            TextBlock.builder()
                                .text("PRIVATE KEY-----\nMIIEowStopBody111")
                                .build(),
                            ThinkingBlock.builder()
                                .thinking("PRIVATE KEY-----\nMHcCStopBody222")
                                .build()))
                    .usage(ChatUsage.builder().inputTokens(12).outputTokens(8).build())
                    .finishReason("stop")
                    .build()));
    new ControlledModel(stopDelegate, ModelMode.MOCK, stopRuntime, new AtomicReference<>())
        .stream(List.of(), List.of(), GenerateOptions.builder().build())
        .collectList()
        .block();
    assertNoPemLeak("自然结束 SSE 正文", stopPub.joined("MODEL_DELTA"), "MIIEowStopBody111");
    assertNoPemLeak("自然结束 SSE 推理", stopPub.joined("MODEL_REASONING_DELTA"), "MHcCStopBody222");
    assertNotNull(stopPayload.get());
    assertNoPemLeak("自然结束快照正文", stopPayload.get().responseContent(), "MIIEowStopBody111");
    assertNoPemLeak("自然结束快照推理", stopPayload.get().reasoningContent(), "MHcCStopBody222");
    assertNoPemLeak(
        "存储层二次 maskAndTruncate 后快照正文",
        dev.workbench.framework.trace.SensitiveDataMasker.maskAndTruncate(
                stopPayload.get().responseContent())
            .text(),
        "MIIEowStopBody111");

    // 2. 限长结束（finishReason=length）：敏感字段内嵌未闭合 PEM，SSE 双通道与快照均不得外泄。
    CapturingPublisher limitPub = new CapturingPublisher();
    var limitPayload = new AtomicReference<GlassboxModels.ModelCallPayload>();
    ControlledAgentRuntime limitRuntime = runtimeWithTrace(limitPub, limitPayload);
    Model limitDelegate =
        stubModel(
            Flux.just(
                ChatResponse.builder()
                    .content(
                        List.of(
                            TextBlock.builder()
                                .text("{\"secret\": \"-----BEGIN RSA PRIVATE KEY-----\nMIIEowLimit333")
                                .build(),
                            ThinkingBlock.builder()
                                .thinking("{\"token\": \"-----BEGIN PRIVATE KEY-----\nMIIEowLimit444")
                                .build()))
                    .usage(ChatUsage.builder().inputTokens(10).outputTokens(5).build())
                    .finishReason("length")
                    .build()));
    new ControlledModel(limitDelegate, ModelMode.MOCK, limitRuntime, new AtomicReference<>())
        .stream(List.of(), List.of(), GenerateOptions.builder().build())
        .collectList()
        .block();
    assertNoPemLeak("限长结束 SSE 正文", limitPub.joined("MODEL_DELTA"), "MIIEowLimit333");
    assertNoPemLeak("限长结束 SSE 推理", limitPub.joined("MODEL_REASONING_DELTA"), "MIIEowLimit444");
    assertNoPemLeak("限长结束快照正文", limitPayload.get().responseContent(), "MIIEowLimit333");
    assertNoPemLeak("限长结束快照推理", limitPayload.get().reasoningContent(), "MIIEowLimit444");

    // 3. 失败中断（onError）
    CapturingPublisher errPub = new CapturingPublisher();
    ControlledAgentRuntime errRuntime = runtimeWithTrace(errPub, new AtomicReference<>());
    Model errDelegate =
        stubModel(
            Flux.concat(
                Flux.just(
                    ChatResponse.builder()
                        .content(
                            List.of(
                                TextBlock.builder()
                                    .text("{\"secret\": \"-----BEGIN RSA PRIVATE KEY-----\nMIIEowErr555")
                                    .build(),
                                ThinkingBlock.builder()
                                    .thinking("token: -----BEGIN RSA PRIVATE KEY-----\nMIIEowErr666")
                                    .build()))
                        .build()),
                Flux.error(new IllegalStateException("stream broken"))));
    assertThrows(
        IllegalStateException.class,
        () ->
            new ControlledModel(errDelegate, ModelMode.MOCK, errRuntime, new AtomicReference<>())
                .stream(List.of(), List.of(), GenerateOptions.builder().build())
                .collectList()
                .block());
    assertNoPemLeak("失败中断 SSE 正文", errPub.joined("MODEL_DELTA"), "MIIEowErr555");
    assertNoPemLeak("失败中断 SSE 推理", errPub.joined("MODEL_REASONING_DELTA"), "MIIEowErr666");

    // 4. 下游取消（doOnCancel via take(1)）
    CapturingPublisher cancelPub = new CapturingPublisher();
    ControlledAgentRuntime cancelRuntime = runtimeWithTrace(cancelPub, new AtomicReference<>());
    Model cancelDelegate =
        stubModel(
            Flux.just(
                ChatResponse.builder()
                    .content(
                        List.of(
                            TextBlock.builder()
                                .text("{\"secret\": \"-----BEGIN RSA PRIVATE KEY-----\nMIIEowCancel777")
                                .build(),
                            ThinkingBlock.builder()
                                .thinking("password: \"-----BEGIN RSA PRIVATE KEY-----\nMIIEowCancel888")
                                .build()))
                    .build(),
                ChatResponse.builder()
                    .content(List.of(TextBlock.builder().text("第二帧").build()))
                    .build()));
    new ControlledModel(cancelDelegate, ModelMode.MOCK, cancelRuntime, new AtomicReference<>())
        .stream(List.of(), List.of(), GenerateOptions.builder().build())
        .take(1)
        .collectList()
        .block();
    assertNoPemLeak("取消中断 SSE 正文", cancelPub.joined("MODEL_DELTA"), "MIIEowCancel777");
    assertNoPemLeak("取消中断 SSE 推理", cancelPub.joined("MODEL_REASONING_DELTA"), "MIIEowCancel888");
  }

  @Test
  void blankTrailingFinishReasonChunkCannotDowngradeEarlierLengthTruncation() {
    CapturingPublisher publisher = new CapturingPublisher();
    var capturedPayload = new AtomicReference<GlassboxModels.ModelCallPayload>();
    ControlledAgentRuntime runtime = runtimeWithTrace(publisher, capturedPayload);
    // 模拟部分兼容供应商在 stream_options.include_usage 下的尾帧序列：先发 length，尾帧仅带 usage 和空白 finishReason。
    Model delegate =
        stubModel(
            Flux.just(
                ChatResponse.builder()
                    .content(
                        List.of(
                            TextBlock.builder().text("Authorization: Bearer opaq42").build(),
                            ThinkingBlock.builder().thinking("{\"api_key\": \"sk-tail").build()))
                    .finishReason("length")
                    .build(),
                ChatResponse.builder()
                    .content(List.of())
                    .usage(ChatUsage.builder().inputTokens(10).outputTokens(5).build())
                    .finishReason("")
                    .build(),
                ChatResponse.builder()
                    .content(List.of())
                    .usage(ChatUsage.builder().inputTokens(10).outputTokens(5).build())
                    .finishReason("   ")
                    .build()));
    new ControlledModel(delegate, ModelMode.MOCK, runtime, new AtomicReference<>())
        .stream(List.of(), List.of(), GenerateOptions.builder().build())
        .collectList()
        .block();

    assertFalse(publisher.joined("MODEL_DELTA").contains("opaq42"), publisher.joined("MODEL_DELTA"));
    assertFalse(
        publisher.joined("MODEL_REASONING_DELTA").contains("sk-tail"),
        publisher.joined("MODEL_REASONING_DELTA"));
    assertFalse(
        capturedPayload.get().responseContent().contains("opaq42"),
        capturedPayload.get().responseContent());
    assertFalse(
        capturedPayload.get().reasoningContent().contains("sk-tail"),
        capturedPayload.get().reasoningContent());
  }

  @Test
  void mixedMaskedPrefixAndPlaintextSuffixNeverLeaksInSseOrSnapshot() {
    CapturingPublisher publisher = new CapturingPublisher();
    var capturedPayload = new AtomicReference<GlassboxModels.ModelCallPayload>();
    ControlledAgentRuntime runtime = runtimeWithTrace(publisher, capturedPayload);
    Model delegate =
        stubModel(
            Flux.just(
                ChatResponse.builder()
                    .content(
                        List.of(
                            TextBlock.builder()
                                .text("secret=AKIAIOSFODNN7EXAMPLE:plain_suffix_body ")
                                .build(),
                            ThinkingBlock.builder()
                                .thinking("{\"token\": \"AKIAIOSFODNN7EXAMPLE:plain_suffix_reason\"} ")
                                .build()))
                    .build(),
                ChatResponse.builder()
                    .content(
                        List.of(
                            TextBlock.builder().text("password=***MASKED***_ab").build(),
                            ThinkingBlock.builder().thinking("password=***MASKED***_cd").build()))
                    .usage(ChatUsage.builder().inputTokens(10).outputTokens(6).build())
                    .finishReason("length")
                    .build()));
    new ControlledModel(delegate, ModelMode.MOCK, runtime, new AtomicReference<>())
        .stream(List.of(), List.of(), GenerateOptions.builder().build())
        .collectList()
        .block();

    String sseContent = publisher.joined("MODEL_DELTA");
    String sseReasoning = publisher.joined("MODEL_REASONING_DELTA");
    assertFalse(sseContent.contains("plain_suffix_body"), sseContent);
    assertFalse(sseContent.contains("_ab"), sseContent);
    assertFalse(sseReasoning.contains("plain_suffix_reason"), sseReasoning);
    assertFalse(sseReasoning.contains("_cd"), sseReasoning);
    assertFalse(capturedPayload.get().responseContent().contains("plain_suffix_body"));
    assertFalse(capturedPayload.get().responseContent().contains("_ab"));
    assertFalse(capturedPayload.get().reasoningContent().contains("plain_suffix_reason"));
    assertFalse(capturedPayload.get().reasoningContent().contains("_cd"));
  }

  private static void assertNoPemLeak(String label, String output, String bodyFragment) {
    assertFalse(output.contains(bodyFragment), label + " 泄露未闭合私钥正文：" + output);
    assertFalse(output.contains("PRIVATE KEY"), label + " 残留未闭合私钥头：" + output);
    assertTrue(output.contains("***MASKED-PRIVATE-KEY***"), label + " 缺少私钥掩码标记：" + output);
  }

  private static Model stubModel(Flux<ChatResponse> stream) {
    return new Model() {
      @Override
      public Flux<ChatResponse> stream(
          List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        return stream;
      }

      @Override
      public String getModelName() {
        return "mock-stream";
      }

      @Override
      public int getContextWindowSize() {
        return 0;
      }
    };
  }

  private static ControlledAgentRuntime runtimeWithTrace(
      ApplicationEventPublisher publisher,
      AtomicReference<GlassboxModels.ModelCallPayload> capturedPayload) {
    RunControl control = mock(RunControl.class);
    when(control.lastModelCallId(any())).thenReturn(UUID.randomUUID());
    when(control.budgetPolicy(any(), any()))
        .thenReturn(new dev.workbench.framework.control.AgentBudgetPolicy(1, null, null, Map.of(), null));
    RunEvidenceRecordService evidence = mock(RunEvidenceRecordService.class);
    when(evidence.eventRefForAgent(any(), any()))
        .thenReturn(dev.workbench.domain.EventRef.empty());
    GlassboxTraceStore traces =
        new GlassboxTraceStore(null, null, null, null) {
          @Override
          public void recordModelPayload(
              String runId, String callId, GlassboxModels.ModelCallPayload payload) {
            capturedPayload.set(payload);
          }
        };
    var runtime =
        new ControlledAgentRuntime(
            UUID.randomUUID().toString(),
            UUID.randomUUID().toString(),
            control,
            mock(AgentAssemblyRecordService.class),
            mock(RunStateStore.class),
            evidence,
            null,
            null,
            null,
            traces,
            "zh-CN",
            null,
            publisher,
            null);
    runtime.assemblyResolved(
        new AgentAssemblySnapshot.Components(
            new AgentDefinitionRef("text-analysis", 1),
            "a".repeat(64),
            new dev.workbench.framework.model.AgentModelRegistry.ModelReference(
                ModelMode.MOCK, ModelKind.MOCK.code(), 1),
            null,
            0,
            List.of(),
            Map.of(),
            new AgentAssemblySnapshot.Authorization(1, AgentRole.ANALYST, 1, null, 0, null, false),
            new AgentAssemblySnapshot.Input("b".repeat(64), List.of()),
            List.of(),
            List.of()));
    return runtime;
  }

  private void assertNoLeak(List<String> contentFrames, List<String> reasoningFrames) {
    CapturingPublisher publisher = new CapturingPublisher();
    ControlledAgentRuntime runtime = runtime(publisher);
    for (String frame : contentFrames) runtime.modelDelta(frame);
    for (String frame : reasoningFrames) runtime.modelReasoningDelta(frame);
    runtime.modelResult(null, null);

    String content = publisher.joined("MODEL_DELTA");
    String reasoning = publisher.joined("MODEL_REASONING_DELTA");

    assertFalse(content.contains(KEY), "SSE 正文泄露了明文 Key：" + content);
    assertFalse(content.contains(JWT), "SSE 正文泄露了明文 JWT：" + content);
    assertFalse(content.contains("Hunter2-Super-Secret"), "SSE 正文泄露了明文口令：" + content);
    assertFalse(content.contains("MIIEowIBAAKCAQEA1234567890"), "SSE 正文泄露了私钥正文：" + content);
    assertFalse(reasoning.contains(JWT), "思考链泄露了明文 JWT：" + reasoning);
    assertFalse(reasoning.contains("Hunter2-Super-Secret"), "思考链泄露了明文口令：" + reasoning);
    // 脱敏不能吞掉正常语义，也不能重复拼接。
    assertTrue(content.startsWith("已核对：key="), content);
    assertTrue(content.contains("其余正常，可以继续。"), content);
    assertTrue(reasoning.startsWith("先检查凭据：token "), reasoning);
    assertTrue(content.contains("***MASKED"), content);
  }

  private static List<String> splitEveryCharacter(String text) {
    List<String> frames = new ArrayList<>();
    for (int index = 0; index < text.length(); index++) frames.add(text.substring(index, index + 1));
    return frames;
  }

  private static List<String> splitRandomly(String text, Random random) {
    List<String> frames = new ArrayList<>();
    int cursor = 0;
    while (cursor < text.length()) {
      int size = 3 + random.nextInt(3); // 3~5 字符
      int end = Math.min(text.length(), cursor + size);
      frames.add(text.substring(cursor, end));
      cursor = end;
    }
    return frames;
  }

  private static ControlledAgentRuntime runtime(ApplicationEventPublisher publisher) {
    return new ControlledAgentRuntime(
        UUID.randomUUID().toString(),
        UUID.randomUUID().toString(),
        mock(RunControl.class),
        mock(AgentAssemblyRecordService.class),
        mock(RunStateStore.class),
        mock(RunEvidenceRecordService.class),
        null,
        null,
        null,
        null,
        "zh-CN",
        null,
        publisher,
        null);
  }

  private static final class CapturingPublisher implements ApplicationEventPublisher {
    private final List<RunStreamTransient> events = new ArrayList<>();

    @Override
    public void publishEvent(Object event) {
      if (event instanceof RunStreamTransient transientEvent) events.add(transientEvent);
    }

    String joined(String kind) {
      StringBuilder text = new StringBuilder();
      for (RunStreamTransient event : events)
        if (kind.equals(event.kind())) text.append(event.text());
      return text.toString();
    }
  }
}
