package dev.workbench.infrastructure.agentscope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.workbench.domain.ModelKind;
import dev.workbench.domain.ModelMode;
import dev.workbench.domain.ToolKind;
import dev.workbench.framework.agent.assembly.AgentAssemblySnapshot;
import dev.workbench.framework.agent.execution.AgentRuntime;
import dev.workbench.framework.trace.GlassboxModels;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/**
 * 真流式下的增量通道与终态快照。
 *
 * <p>流式模式里每个 {@code ChatResponse} 只带一个片段。这里锁住三条不可退化的行为：
 * 正文与思考链走不同通道；终态快照由全部片段累积而成；单帧（非流式或模拟模型）不标记为流式。
 */
class ControlledModelStreamingTest {

  /** 只记录运行时关心的输出，避免依赖真实的费用与事件存储。 */
  private static final class RecordingRuntime implements AgentRuntime {
    private final List<String> deltas = new ArrayList<>();
    private final List<String> reasoningDeltas = new ArrayList<>();
    private GlassboxModels.ModelCallPayload payload;
    private Integer inputTokens;
    private Integer outputTokens;

    @Override
    public void beforeModel(ModelKind modelName) {}

    @Override
    public void modelResult(Integer input, Integer output) {
      inputTokens = input;
      outputTokens = output;
    }

    @Override
    public void modelDelta(String text) {
      deltas.add(text);
    }

    @Override
    public void modelReasoningDelta(String text) {
      reasoningDeltas.add(text);
    }

    @Override
    public void modelTrace(GlassboxModels.ModelCallPayload value) {
      payload = value;
    }

    @Override
    public void assemblyResolved(AgentAssemblySnapshot.Components components) {}

    @Override
    public UUID beforeTool(ToolKind tool, int version, String fingerprint) {
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
  }

  private static ChatResponse chunk(ChatUsage usage, ContentBlock... blocks) {
    return ChatResponse.builder().content(List.of(blocks)).usage(usage).build();
  }

  private static Model streamingModel(List<ChatResponse> chunks) {
    return new Model() {
      @Override
      public Flux<ChatResponse> stream(
          List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        return Flux.fromIterable(chunks);
      }

      @Override
      public String getModelName() {
        return "streaming-stub";
      }

      @Override
      public int getContextWindowSize() {
        return 0;
      }
    };
  }

  private static ControlledModel model(Model delegate, AgentRuntime runtime) {
    return new ControlledModel(
        delegate, ModelMode.MOCK, runtime, new AtomicReference<RuntimeException>());
  }

  @Test
  void separatesReasoningFromContentAndAccumulatesTerminalSnapshot() {
    var runtime = new RecordingRuntime();
    var usage = ChatUsage.builder().inputTokens(11).outputTokens(7).build();
    var delegate =
        streamingModel(
            List.of(
                chunk(null, ThinkingBlock.builder().thinking("先看授权文件").build()),
                chunk(null, ThinkingBlock.builder().thinking("，确认范围").build()),
                chunk(null, TextBlock.builder().text("结论：").build()),
                chunk(
                    usage,
                    TextBlock.builder().text("可以修改。").build(),
                    ToolUseBlock.builder()
                        .id("call-1")
                        .name("write_file")
                        .input(Map.of("path", "src/App.jsx"))
                        .build())));

    var result = model(delegate, runtime).stream(List.of(), List.of(), null).collectList().block();

    // 四个片段必须原样透传给上层 Agent，否则 ReAct 累积内容会被切断。
    assertEquals(4, result == null ? 0 : result.size());
    assertEquals(List.of("结论：", "可以修改。"), runtime.deltas);
    assertEquals(List.of("先看授权文件", "，确认范围"), runtime.reasoningDeltas);
    assertEquals(11, runtime.inputTokens);
    assertEquals(7, runtime.outputTokens);

    var payload = runtime.payload;
    assertTrue(payload.streamed(), "多帧响应必须标记为流式");
    assertEquals("结论：可以修改。", payload.responseContent());
    assertEquals("先看授权文件，确认范围", payload.reasoningContent());
    assertEquals(1, payload.responseToolCalls().size());
    assertEquals("write_file", payload.responseToolCalls().getFirst().name());
  }

  @Test
  void singleChunkIsNotMarkedAsStreamed() {
    var runtime = new RecordingRuntime();
    var delegate =
        streamingModel(List.of(chunk(null, TextBlock.builder().text("一次性返回").build())));

    model(delegate, runtime).stream(List.of(), List.of(), null).blockLast();

    assertFalse(runtime.payload.streamed());
    assertEquals("一次性返回", runtime.payload.responseContent());
    assertEquals("", runtime.payload.reasoningContent());
  }

  @Test
  void cumulativeProviderFramesDoNotDuplicateContent() {
    var runtime = new RecordingRuntime();
    // 某些 OpenAI 兼容网关每帧都发累积全文，而不是增量片段。
    var delegate =
        streamingModel(
            List.of(
                chunk(null, TextBlock.builder().text("第一句").build()),
                chunk(null, TextBlock.builder().text("第一句第二句").build()),
                chunk(null, TextBlock.builder().text("第一句第二句第三句").build())));

    model(delegate, runtime).stream(List.of(), List.of(), null).blockLast();

    assertEquals("第一句第二句第三句", runtime.payload.responseContent());
    // 推给前端的必须是净增量，否则打字机会把前两句重复拼三遍。
    assertEquals(List.of("第一句", "第二句", "第三句"), runtime.deltas);
  }

  @Test
  void cumulativeReasoningFramesAlsoEmitNetIncrements() {
    var runtime = new RecordingRuntime();
    var delegate =
        streamingModel(
            List.of(
                chunk(null, ThinkingBlock.builder().thinking("先看").build()),
                chunk(null, ThinkingBlock.builder().thinking("先看目录").build()),
                chunk(null, ThinkingBlock.builder().thinking("先看目录再读文件").build())));

    model(delegate, runtime).stream(List.of(), List.of(), null).blockLast();

    assertEquals(List.of("先看", "目录", "再读文件"), runtime.reasoningDeltas);
    assertEquals("先看目录再读文件", runtime.payload.reasoningContent());
  }

  @Test
  void repeatedIdenticalFrameContributesNoDelta() {
    var runtime = new RecordingRuntime();
    // 心跳式重复帧（内容与上一帧完全相同）不产生净增量，避免前端空转。
    var delegate =
        streamingModel(
            List.of(
                chunk(null, TextBlock.builder().text("稳定").build()),
                chunk(null, TextBlock.builder().text("稳定").build())));

    model(delegate, runtime).stream(List.of(), List.of(), null).blockLast();

    assertEquals(List.of("稳定"), runtime.deltas);
    assertEquals("稳定", runtime.payload.responseContent());
  }

  @Test
  void streamingFailurePropagatesAndKeepsFailureSignal() {
    var failure = new AtomicReference<RuntimeException>();
    var runtime = new RecordingRuntime();
    Model broken =
        new Model() {
          @Override
          public Flux<ChatResponse> stream(
              List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.concat(
                Flux.just(chunk(null, TextBlock.builder().text("部分").build())),
                Flux.error(new IllegalStateException("供应商中断")));
          }

          @Override
          public String getModelName() {
            return "broken";
          }

          @Override
          public int getContextWindowSize() {
            return 0;
          }
        };
    var controlled = new ControlledModel(broken, ModelMode.MOCK, runtime, failure);

    String thrown = null;
    try {
      controlled.stream(List.of(), List.of(), null).blockLast();
    } catch (RuntimeException expected) {
      // 预期抛出：错误不能被吞成「成功但内容为空」。
      thrown = expected.getMessage();
    }
    assertEquals("供应商中断", thrown);
    // 中途失败不产生终态快照，避免把半截正文当成完整回答写入证据。
    assertEquals(null, runtime.payload);
  }
}
