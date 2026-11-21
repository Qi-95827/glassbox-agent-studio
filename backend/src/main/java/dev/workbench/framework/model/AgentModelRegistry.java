package dev.workbench.framework.model;

import dev.workbench.domain.ModelMode;
import dev.workbench.framework.agent.execution.AgentExecutionRequest;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
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

  public ModelClient create(AgentExecutionRequest request) {
    return createChecked(providerFor(request.modelMode()), request);
  }

  public SelectedModel select(AgentExecutionRequest request) {
    var provider = providerFor(request.modelMode());
    ModelClient model = createChecked(provider, request);
    return new SelectedModel(
        model,
        new ModelReference(request.modelMode(), model.modelName(), provider.configurationVersion()),
        provider::isRateLimitRejection);
  }

  private AgentModelProvider providerFor(ModelMode mode) {
    var provider = providers.get(mode);
    if (provider == null) throw new IllegalStateException("未配置所选模型模式的提供者。");
    return provider;
  }

  private static ModelClient createChecked(
      AgentModelProvider provider, AgentExecutionRequest request) {
    ModelClient model = provider.create(request);
    if (model == null) throw new IllegalStateException("模型提供者未创建模型。");
    return model;
  }

  public record SelectedModel(
      ModelClient client, ModelReference reference, Predicate<Throwable> rateLimitRejection) {
    public SelectedModel(ModelClient client, ModelReference reference) {
      this(client, reference, error -> false);
    }
  }

  /** 已选模型的公开名称与服务端配置版本。 */
  public record ModelReference(ModelMode mode, String name, int configurationVersion) {
    public ModelReference {
      if (mode == null
          || name == null
          || !name.matches("[A-Za-z0-9._:/-]{1,120}")
          || configurationVersion < 1) throw new IllegalArgumentException("模型公开标识或配置版本无效。");
    }
  }
}
