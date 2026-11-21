package dev.workbench.infrastructure.agentscope;

import dev.workbench.framework.model.ModelRateLimitException;
import dev.workbench.domain.EventType;
import dev.workbench.domain.ModelKind;
import dev.workbench.domain.ModelMode;
import dev.workbench.framework.agent.execution.AgentRuntime;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import reactor.core.publisher.Flux;

/** AgentScope Model 包装器：每次真实请求前执行预算检查并记录用量。 */
public record ControlledModel(
    Model delegate,
    ModelMode mode,
    AgentRuntime runtime,
    AtomicReference<RuntimeException> failure,
    Predicate<Throwable> rateLimitRejection)
    implements Model {
  public ControlledModel(
      Model delegate,
      ModelMode mode,
      AgentRuntime runtime,
      AtomicReference<RuntimeException> failure) {
    this(delegate, mode, runtime, failure, error -> false);
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
            StringBuilder inputText = new StringBuilder();
            for (Msg message : messages) {
              if (message.getTextContent() != null) inputText.append(message.getTextContent()).append('\n');
            }
            inputText.append(tools);
            runtime.verifyModelInput(inputText.toString());
            GenerateOptions constrained = constrainedOptions(options, runtime.maxOutputTokens());
            runtime.event(EventType.MODEL_STARTED, "AgentScope 开始模型调用：" + delegate.getModelName());
            AtomicReference<ChatResponse> latest = new AtomicReference<>();
            AtomicReference<ChatUsage> latestUsage = new AtomicReference<>();
            return Flux.defer(() -> delegate.stream(messages, tools, constrained))
                .doOnNext(
                    response -> {
                      latest.set(response);
                      if (response.getUsage() != null) latestUsage.set(response.getUsage());
                    })
                .doOnComplete(
                    () -> {
                      try {
                        if (latest.get() == null) throw new IllegalStateException("模型未返回响应。");
                        var usage = latestUsage.get();
                        runtime.modelResult(
                            usage == null ? null : usage.getInputTokens(),
                            usage == null ? null : usage.getOutputTokens());
                        runtime.event(EventType.MODEL_FINISHED, "AgentScope 模型已返回。");
                      } catch (RuntimeException e) {
                        failure.set(e);
                        throw e;
                      }
                    })
                .onErrorMap(
                    error -> {
                      if (latest.get() != null || !rateLimitRejection.test(error)) return error;
                      var rejected = new ModelRateLimitException();
                      // 使用安全消息传播拒绝原因，不将供应商响应正文写入轨迹。
                      failure.compareAndSet(null, rejected);
                      return failure.get();
                    });
          } catch (RuntimeException e) {
            failure.set(e);
            return Flux.error(e);
          }
        });
  }

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
}