package dev.workbench.framework.model;

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
import reactor.core.publisher.Flux;

/** AgentScope Model 包装器：每次真实请求前执行预算检查并记录用量。 */
public record ControlledModel(
    Model delegate, ModelMode mode, AgentRuntime runtime, AtomicReference<RuntimeException> failure)
    implements Model {
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
          try {
            runtime.beforeModel(
                mode == ModelMode.DEEPSEEK ? ModelKind.DEEPSEEK_FLASH : ModelKind.MOCK);
            runtime.event(EventType.MODEL_STARTED, "AgentScope 开始模型调用：" + delegate.getModelName());
            AtomicReference<ChatResponse> latest = new AtomicReference<>();
            AtomicReference<ChatUsage> latestUsage = new AtomicReference<>();
            return delegate.stream(messages, tools, options)
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
                    });
          } catch (RuntimeException e) {
            failure.set(e);
            return Flux.error(e);
          }
        });
  }
}
