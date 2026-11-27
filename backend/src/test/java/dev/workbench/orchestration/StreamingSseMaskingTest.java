package dev.workbench.orchestration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import dev.workbench.framework.control.RunControl;
import dev.workbench.framework.event.RunStreamTransient;
import dev.workbench.store.AgentAssemblyRecordService;
import dev.workbench.store.RunEvidenceRecordService;
import dev.workbench.store.RunStateStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

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
