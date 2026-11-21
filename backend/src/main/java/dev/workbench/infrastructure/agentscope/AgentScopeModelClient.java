package dev.workbench.infrastructure.agentscope;

import dev.workbench.framework.model.ModelClient;
import io.agentscope.core.model.Model;

/** 将 AgentScope Model 暴露为项目自有 ModelClient，SDK 类型只在本包内出现。 */
public final class AgentScopeModelClient implements ModelClient {
  private final Model delegate;

  public AgentScopeModelClient(Model delegate) {
    if (delegate == null) throw new IllegalArgumentException("AgentScope 模型不能为空。");
    this.delegate = delegate;
  }

  public Model model() {
    return delegate;
  }

  @Override
  public Object nativeClient() {
    return delegate;
  }

  @Override
  public String modelName() {
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
  public int contextWindowSize() {
    return delegate.getContextWindowSize();
  }
}
