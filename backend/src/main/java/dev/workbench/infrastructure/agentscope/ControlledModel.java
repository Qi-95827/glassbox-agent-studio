package dev.workbench.infrastructure.agentscope;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.workbench.domain.EventType;
import dev.workbench.domain.ModelKind;
import dev.workbench.domain.ModelMode;
import dev.workbench.framework.agent.execution.AgentRuntime;
import dev.workbench.framework.context.CompactionRequest;
import dev.workbench.framework.context.CompactionScope;
import dev.workbench.framework.context.ContextBreakdown;
import dev.workbench.framework.context.ContextCompactionHook;
import dev.workbench.framework.context.ContextMessage;
import dev.workbench.framework.model.ModelRateLimitException;
import dev.workbench.framework.trace.GlassboxModels;
import dev.workbench.framework.trace.SensitiveDataMasker;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import reactor.core.publisher.Flux;

/**
 * AgentScope Model 包装器：每次真实请求前执行预算检查、四级上下文压缩并记录用量。
 *
 * <p>{@code compactionScope} 携带当前运行标识、BYOK 连接与用量回调：压缩发生在后台线程，没有 HTTP 会话，只有显式透传才能避免 L2 摘要回退到 {@code
 * LOCAL} 默认连接而盗用他人密钥与额度。
 */
public record ControlledModel(
    Model delegate,
    ModelMode mode,
    AgentRuntime runtime,
    AtomicReference<RuntimeException> failure,
    Predicate<Throwable> rateLimitRejection,
    ContextCompactionHook compactionHook,
    CompactionScope compactionScope,
    AtomicReference<String> incrementalCheckpoint)
    implements Model {
  public ControlledModel(
      Model delegate,
      ModelMode mode,
      AgentRuntime runtime,
      AtomicReference<RuntimeException> failure) {
    this(delegate, mode, runtime, failure, error -> false, null, CompactionScope.NONE, null);
  }

  public ControlledModel(
      Model delegate,
      ModelMode mode,
      AgentRuntime runtime,
      AtomicReference<RuntimeException> failure,
      Predicate<Throwable> rateLimitRejection) {
    this(delegate, mode, runtime, failure, rateLimitRejection, null, CompactionScope.NONE, null);
  }

  public ControlledModel(
      Model delegate,
      ModelMode mode,
      AgentRuntime runtime,
      AtomicReference<RuntimeException> failure,
      Predicate<Throwable> rateLimitRejection,
      ContextCompactionHook compactionHook) {
    this(
        delegate,
        mode,
        runtime,
        failure,
        rateLimitRejection,
        compactionHook,
        CompactionScope.NONE,
        null);
  }

  public ControlledModel(
      Model delegate,
      ModelMode mode,
      AgentRuntime runtime,
      AtomicReference<RuntimeException> failure,
      Predicate<Throwable> rateLimitRejection,
      ContextCompactionHook compactionHook,
      CompactionScope compactionScope) {
    this(
        delegate,
        mode,
        runtime,
        failure,
        rateLimitRejection,
        compactionHook,
        compactionScope,
        null);
  }

  public String getModelName() {
    return delegate.getModelName();
  }

  @Override
  public boolean supportsNativeStructuredOutput() {
    return delegate.supportsNativeStructuredOutput();
  }

  @Override
  public boolean supportsNativeStructuredOutputWithTools() {
    return delegate.supportsNativeStructuredOutputWithTools();
  }

  @Override
  public int getContextWindowSize() {
    return delegate.getContextWindowSize();
  }

  public Flux<ChatResponse> stream(
      List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
    return Flux.defer(
        () -> {
          // SDK 可能收到工具错误后继续推理；已记录的停止信号必须先阻止新的调用。
          var stopped = failure.get();
          if (stopped != null) return Flux.error(stopped);
          try {
            runtime.beforeModel(ModelKind.forMode(mode));
            List<Msg> effective = messages;
            ContextBreakdown context = null;
            if (compactionHook != null && delegate.getContextWindowSize() > 0) {
              var compacted = compact(messages);
              if (compacted != null) {
                effective = compacted.messages();
                context = compacted.breakdown();
                if (context.compacted()) runtime.contextCompacted(context);
              }
            }
            // 输入体积校验必须计入工具返回值：只统计正文会让超限输入直接打到供应商侧。
            StringBuilder inputText = new StringBuilder();
            for (Msg message : effective) inputText.append(expandBlocks(message)).append('\n');
            inputText.append(tools);
            runtime.verifyModelInput(inputText.toString());
            GenerateOptions constrained = constrainedOptions(options, runtime.maxOutputTokens());
            runtime.event(EventType.MODEL_STARTED, "AgentScope 开始模型调用：" + delegate.getModelName());
            AtomicReference<ChatResponse> latest = new AtomicReference<>();
            AtomicReference<ChatUsage> latestUsage = new AtomicReference<>();
            ContextBreakdown breakdown = context;
            List<Msg> request = effective;
            // 累积器必须与订阅同寿命：SDK 的内部重试会重新订阅同一个 Flux，
            // 若把累积器建在外层，重试会把上一轮的部分正文再拼一遍。
            return Flux.defer(
                    () -> {
                      var accumulator = new StreamAccumulator();
                      return delegate
                          .stream(request, tools, constrained)
                          .doOnNext(
                              response -> {
                                latest.set(response);
                                if (response.getUsage() != null)
                                  latestUsage.set(response.getUsage());
                                var deltas = accumulator.accept(response);
                                emitDeltas(deltas);
                              })
                          .doOnComplete(
                              () -> {
                                try {
                                  if (latest.get() == null)
                                    throw new IllegalStateException("模型未返回响应。");
                                  runtime.modelFinishReason(accumulator.finishReason());
                                  var usage = latestUsage.get();
                                  runtime.modelResult(
                                      usage == null ? null : usage.getInputTokens(),
                                      usage == null ? null : usage.getOutputTokens());
                                  runtime.modelTrace(
                                      buildPayload(request, tools, accumulator, breakdown));
                                  runtime.event(EventType.MODEL_FINISHED, "AgentScope 模型已返回。");
                                } catch (RuntimeException e) {
                                  failure.set(e);
                                  throw e;
                                }
                              })
                          .onErrorMap(
                              error -> {
                                // 失败或取消时必须显式释放预留：否则一次失败的调用会永久占用运行费用额度。
                                releaseReservation();
                                if (latest.get() != null || !rateLimitRejection.test(error))
                                  return error;
                                var rejected = new ModelRateLimitException();
                                // 使用安全消息传播拒绝原因，不将供应商响应正文写入轨迹。
                                failure.compareAndSet(null, rejected);
                                return failure.get();
                              })
                          .doOnCancel(this::releaseReservation);
                    });
          } catch (RuntimeException e) {
            // beforeModel 已经预留费用；此处的停止信号/输入校验异常同样必须释放预留。
            releaseReservation();
            failure.set(e);
            return Flux.error(e);
          }
        });
  }

  /** 失败/取消时释放本轮的模型费用预留；运行时自身保证重复释放是幂等的。 */
  private void releaseReservation() {
    runtime.modelFailed();
  }

  /**
   * 调用四级压缩并把结果映射回 AgentScope 消息。
   *
   * <p>保留下来的消息按 {@code originalIndex} 精确复用原对象，{@code tool_use}/{@code tool_result} 等结构化
   * 块不会丢失；只有被压缩器改写或新生成的文本才重建消息。上一轮 L2 检查点会被带入下一轮做增量合并。
   */
  private Compacted compact(List<Msg> messages) {
    try {
      var scope = compactionScope.withUsage(runtime::auxiliaryModelUsage);
      String previous = incrementalCheckpoint == null ? "" : incrementalCheckpoint.get();
      var request =
          CompactionRequest.of(toContextMessages(messages), delegate.getContextWindowSize(), scope);
      if (previous != null && !previous.isBlank())
        request = request.withPreviousCheckpoint(previous);
      var result = compactionHook.compact(request);
      if (incrementalCheckpoint != null) {
        String anchors = result.breakdown().structuredAnchors();
        if (anchors != null && !anchors.isBlank()) incrementalCheckpoint.set(anchors);
      }
      return new Compacted(rebuild(messages, result.messages()), result.breakdown());
    } catch (dev.workbench.framework.control.StopExecution stop) {
      // 预算/取消类停止信号不能被“压缩失败即降级”吞掉。
      throw stop;
    } catch (RuntimeException ignored) {
      // 压缩适配失败时保留原请求；预算检查仍会阻止超限输入。
      return null;
    }
  }

  /**
   * 把 SDK 消息完整展开为可压缩文本，并记录原始下标。
   *
   * <p>只取 {@code getTextContent()} 会丢掉 {@code ToolUseBlock} 的名称/参数与 {@code ToolResultBlock}
   * 的返回值：上下文估算严重偏低、压缩看不到工具语义、玻璃盒快照也无法还原。
   */
  private static List<ContextMessage> toContextMessages(List<Msg> messages) {
    var result = new ArrayList<ContextMessage>(messages.size());
    for (int index = 0; index < messages.size(); index++) {
      var message = messages.get(index);
      result.add(
          new ContextMessage(
              message.getRole() == null ? "USER" : message.getRole().name(),
              message.getName(),
              expandBlocks(message),
              toolCallIdOf(message),
              index));
    }
    return result;
  }

  /**
   * 把一条消息的全部结构化块展开成可估算、可脱敏的文本。
   *
   * <p>压缩、输入体积校验与玻璃盒快照必须使用同一份展开结果：只取 {@code getTextContent()} 会漏掉 {@code tool_use} 的参数与 {@code
   * tool_result} 的返回值，既低估体积也让快照失真。
   */
  private static String expandBlocks(Msg message) {
    if (message.getContent() == null)
      return message.getTextContent() == null ? "" : message.getTextContent();
    var text = new StringBuilder();
    for (ContentBlock block : message.getContent()) {
      if (block instanceof TextBlock textBlock) {
        if (textBlock.getText() != null) text.append(textBlock.getText());
        continue;
      }
      if (block instanceof ToolUseBlock use) {
        Object input = use.getInput();
        String args = input == null ? "{}" : String.valueOf(TRACE_JSON.valueToTree(input));
        text.append("[tool_use name=")
            .append(use.getName())
            .append(" id=")
            .append(use.getId())
            .append(" args=")
            .append(args)
            .append("]\n");
        continue;
      }
      if (block instanceof ToolResultBlock toolResult) {
        text.append("[tool_result id=").append(toolResult.getId()).append("] ");
        if (toolResult.getOutput() != null) {
          for (ContentBlock output : toolResult.getOutput()) {
            if (output instanceof TextBlock outputText && outputText.getText() != null)
              text.append(outputText.getText());
            else text.append("[block ").append(output.getClass().getSimpleName()).append(']');
          }
        }
        text.append('\n');
        continue;
      }
      // 其他块（图片/思考等）保留类型标记，避免估算与玻璃盒完全失明。
      text.append("[block ").append(block.getClass().getSimpleName()).append(']');
    }
    if (text.isEmpty() && message.getTextContent() != null) return message.getTextContent();
    return text.toString();
  }

  /** 工具结果优先：一条消息同时含调用与结果时，结果 id 才是配对依据。 */
  private static String toolCallIdOf(Msg message) {
    if (message.getContent() == null) return null;
    for (ContentBlock block : message.getContent())
      if (block instanceof ToolResultBlock result && result.getId() != null) return result.getId();
    for (ContentBlock block : message.getContent())
      if (block instanceof ToolUseBlock use && use.getId() != null) return use.getId();
    return null;
  }

  /**
   * 逐条还原压缩后的消息。
   *
   * <p>优先按下标复用原始 {@code Msg}（保留全部结构化块）；只有被改写或新生成的消息才重新构建， 并且工具结果会带原 {@code callId}
   * 重建，确保调用与结果仍然成对。旧实现用「角色 + 正文」队列复用， 相同正文的相邻消息会串位。
   */
  private static List<Msg> rebuild(List<Msg> original, List<ContextMessage> compacted) {
    var result = new ArrayList<Msg>(compacted.size());
    for (var message : compacted) {
      Integer index = message.originalIndex();
      if (index != null && index >= 0 && index < original.size()) {
        result.add(original.get(index));
        continue;
      }
      MsgRole role;
      try {
        role = MsgRole.valueOf(message.role());
      } catch (IllegalArgumentException e) {
        role = MsgRole.USER;
      }
      var builder = Msg.builder().role(role).name(message.name());
      if (role == MsgRole.TOOL && message.toolCallId() != null) {
        // 工具结果必须以 ToolResultBlock 重建：既保持调用配对，也避免 textContent 再叠加一个 TextBlock。
        builder.content(
            ToolResultBlock.of(
                message.toolCallId(),
                message.name(),
                TextBlock.builder().text(message.content()).build()));
      } else {
        builder.textContent(message.content());
      }
      result.add(builder.build());
    }
    return result;
  }

  private record Compacted(List<Msg> messages, ContextBreakdown breakdown) {}

  /**
   * 按价格版本的输出上界收紧请求；已有更小上限时保留更小值。
   *
   * <p>同时设置 maxTokens 和 maxCompletionTokens，覆盖不同供应方的字段约定。该约束仍以供应方实际遵守情况为界，超支由结算账本记录。
   */
  private static GenerateOptions constrainedOptions(GenerateOptions options, Integer maxOutput) {
    if (maxOutput == null || maxOutput < 1) return options;
    Integer existingMax = options == null ? null : options.getMaxTokens();
    Integer existingCompletion = options == null ? null : options.getMaxCompletionTokens();
    int cap = maxOutput;
    if (existingMax != null) cap = Math.min(cap, existingMax);
    if (existingCompletion != null) cap = Math.min(cap, existingCompletion);
    var limit = GenerateOptions.builder().maxTokens(cap).maxCompletionTokens(cap).build();
    return GenerateOptions.mergeOptions(limit, options);
  }

  private static final ObjectMapper TRACE_JSON = new ObjectMapper();

  /**
   * 把一份流式响应的**净增量**分别推给前端。
   *
   * <p>正文与思考链分成两条通道：思考链不是最终答案，混进正文的「打字机」会让人误以为
   * 那就是答案。两者都只走瞬态 SSE，不落库；落库事实由完成时的透视快照承担。
   *
   * <p>只推净增量而不是原始帧内容：部分兼容网关每帧都返回累积全文，直接转发会让前端
   * 把同一段话反复拼接。净增量由累积器统一计算，供应商语义差异被收敛在一处。
   */
  private void emitDeltas(StreamDeltas deltas) {
    if (deltas == null) return;
    if (deltas.text() != null && !deltas.text().isEmpty()) runtime.modelDelta(deltas.text());
    if (deltas.reasoning() != null && !deltas.reasoning().isEmpty())
      runtime.modelReasoningDelta(deltas.reasoning());
  }

  /** 单帧带来的净增量：正文与思考链各自独立，可能任一为空。 */
  private record StreamDeltas(String text, String reasoning) {
    private static final StreamDeltas EMPTY = new StreamDeltas("", "");

    boolean isEmpty() {
      return text.isEmpty() && reasoning.isEmpty();
    }
  }

  /**
   * 一次模型调用的增量累积器。
   *
   * <p>流式模式下每个 {@code ChatResponse} 只带一个增量片段，不能再像非流式那样「取最后一个
   * 响应」当结果；否则玻璃盒只会记录到最后几个字。这里把全部片段累积成完整响应。
   *
   * <p>同时兼容两类供应商：只发增量的（OpenAI 标准）和每帧发累积全文的。判据是
   * 「新片段以已累积内容开头且不更短」——那是累积语义，直接替换而不是追加。
   */
  private static final class StreamAccumulator {
    private final StringBuilder content = new StringBuilder();
    private final StringBuilder reasoning = new StringBuilder();
    private final java.util.Map<String, ToolUseBlock> toolUses = new java.util.LinkedHashMap<>();
    private int chunks;
    private String finishReason;

    /**
     * 吸收一帧，并返回这一帧带来的**净增量**。
     *
     * <p>净增量与「累积后全文」解耦：增量型供应商直接得到片段本身，累积型供应商得到
     * 「新全文去掉旧全文」的尾巴。前端的打字机因此不需要知道供应商用的是哪种语义。
     */
    StreamDeltas accept(ChatResponse response) {
      if (response == null) return StreamDeltas.EMPTY;
      chunks++;
      if (response.getFinishReason() != null) finishReason = response.getFinishReason();
      if (response.getContent() == null) return StreamDeltas.EMPTY;
      var textDelta = new StringBuilder();
      var reasoningDelta = new StringBuilder();
      for (ContentBlock block : response.getContent()) {
        if (block instanceof TextBlock text) textDelta.append(append(content, text.getText()));
        else if (block instanceof ThinkingBlock thinking)
          reasoningDelta.append(append(reasoning, thinking.getThinking()));
        else if (block instanceof ToolUseBlock use && use.getId() != null)
          // 同一 tool_call 的参数可能分多帧到达，后到的更完整，按 id 取最后一份。
          toolUses.put(use.getId(), use);
      }
      return new StreamDeltas(textDelta.toString(), reasoningDelta.toString());
    }

    /**
     * 追加片段并返回净增量。
     *
     * <p>判据是「新片段以已累积内容开头且不更短」——那是累积语义，此时净增量是尾部差集，
     * 否则按增量语义直接追加。
     */
    private static String append(StringBuilder target, String fragment) {
      if (fragment == null || fragment.isEmpty()) return "";
      String current = target.toString();
      if (!current.isEmpty()
          && fragment.length() >= current.length()
          && fragment.startsWith(current)) {
        String delta = fragment.substring(current.length());
        target.setLength(0);
        target.append(fragment);
        return delta;
      }
      target.append(fragment);
      return fragment;
    }

    String content() {
      return content.toString();
    }

    String reasoning() {
      return reasoning.toString();
    }

    List<ToolUseBlock> toolUses() {
      return new ArrayList<>(toolUses.values());
    }

    boolean streamed() {
      return chunks > 1;
    }

    String finishReason() {
      return finishReason;
    }
  }

  /** 提取发给模型的消息切片、工具 Schema 以及原始响应/工具决策，脱敏后供玻璃盒透视使用。 */
  private static GlassboxModels.ModelCallPayload buildPayload(
      List<Msg> messages,
      List<ToolSchema> tools,
      StreamAccumulator accumulator,
      ContextBreakdown context) {
    boolean truncated = false;
    var reqMessages = new ArrayList<GlassboxModels.PromptMessage>();
    if (messages != null) {
      for (Msg msg : messages) {
        // 玻璃盒必须看到真实请求内容：工具调用参数与工具返回值同样是模型输入的一部分。
        String text = expandBlocks(msg);
        var safe = SensitiveDataMasker.maskAndTruncate(text, 16_384);
        if (safe.truncated()) truncated = true;
        String role = msg.getRole() == null ? "USER" : msg.getRole().name();
        reqMessages.add(
            new GlassboxModels.PromptMessage(role, msg.getName(), safe.text(), text.length()));
      }
    }
    var reqTools = new ArrayList<GlassboxModels.ToolSchemaView>();
    if (tools != null) {
      for (ToolSchema tool : tools) {
        String params = safeJson(tool.getParameters());
        var safeParams = SensitiveDataMasker.maskAndTruncate(params, 8_192);
        if (safeParams.truncated()) truncated = true;
        reqTools.add(
            new GlassboxModels.ToolSchemaView(
                tool.getName(), tool.getDescription(), safeParams.text()));
      }
    }
    String responseText = accumulator == null ? "" : accumulator.content();
    String reasoningText = accumulator == null ? "" : accumulator.reasoning();
    var toolCalls = new ArrayList<GlassboxModels.ToolCallDecision>();
    if (accumulator != null) {
      for (ToolUseBlock tub : accumulator.toolUses()) {
        String args = safeJson(tub.getInput());
        var safeArgs = SensitiveDataMasker.maskAndTruncate(args, 8_192);
        if (safeArgs.truncated()) truncated = true;
        toolCalls.add(
            new GlassboxModels.ToolCallDecision(tub.getId(), tub.getName(), safeArgs.text()));
      }
    }
    boolean truncatedByModel =
        accumulator != null
            && SensitiveDataMasker.isTruncatedFinishReason(accumulator.finishReason());
    var safeResp = SensitiveDataMasker.maskResponseAndTruncate(responseText, 32_768, truncatedByModel);
    if (safeResp.truncated()) truncated = true;
    var safeReasoning =
        SensitiveDataMasker.maskResponseAndTruncate(reasoningText, 32_768, truncatedByModel);
    if (safeReasoning.truncated()) truncated = true;
    return new GlassboxModels.ModelCallPayload(
        reqMessages,
        reqTools,
        safeResp.text(),
        toolCalls,
        truncated,
        context,
        safeReasoning.text(),
        accumulator != null && accumulator.streamed());
  }

  private static String safeJson(Object value) {
    if (value == null) return "{}";
    try {
      return TRACE_JSON.writeValueAsString(value);
    } catch (Exception e) {
      return String.valueOf(value);
    }
  }
}
