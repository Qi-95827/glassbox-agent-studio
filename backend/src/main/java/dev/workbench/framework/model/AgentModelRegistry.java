package dev.workbench.framework.model;

import dev.workbench.domain.ModelMode;
import dev.workbench.framework.agent.execution.AgentExecutionRequest;
import io.agentscope.core.model.Model;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** 依据显式模型模式选择适配器；不会在启动时创建真实模型或读取密钥。 */
@Component
public final class AgentModelRegistry {
  private final Map<ModelMode, AgentModelProvider> providers;

  public AgentModelRegistry(List<AgentModelProvider> candidates) {
    var checked = new EnumMap<ModelMode, AgentModelProvider>(ModelMode.class);
    for (var provider : candidates) {
      if (provider == null
          || provider.mode() == null
          || checked.putIfAbsent(provider.mode(), provider) != null)
        throw new IllegalArgumentException("模型提供者缺少模式或发生重复。");
    }
    providers = Map.copyOf(checked);
  }

  public Model create(AgentExecutionRequest request) {
    var provider = providers.get(request.modelMode());
    if (provider == null) throw new IllegalStateException("未配置所选模型模式的提供者。");
    Model model = provider.create(request);
    if (model == null) throw new IllegalStateException("模型提供者未创建模型。");
    return model;
  }
}
