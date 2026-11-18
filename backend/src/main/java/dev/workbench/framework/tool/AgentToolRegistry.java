package dev.workbench.framework.tool;

import dev.workbench.domain.ToolKind;
import io.agentscope.core.tool.Toolkit;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** 按授权装配计划注册工具，并在模型运行前拒绝不一致。 */
@Component
public final class AgentToolRegistry {
  private final Map<ToolKind, AgentToolProvider> providers;

  public AgentToolRegistry(List<AgentToolProvider> candidates) {
    var checked = new EnumMap<ToolKind, AgentToolProvider>(ToolKind.class);
    for (var provider : candidates) {
      if (provider == null
          || provider.kind() == null
          || checked.putIfAbsent(provider.kind(), provider) != null)
        throw new IllegalArgumentException("工具提供者缺少类型或发生重复。");
    }
    providers = Map.copyOf(checked);
  }

  /** 只注册计划中的工具；成功注册后调用方才能记录装配事实。 */
  public Toolkit assemble(Map<ToolKind, Integer> planned, AgentToolContext context) {
    var toolkit = new Toolkit();
    var sorted = new EnumMap<ToolKind, Integer>(ToolKind.class);
    sorted.putAll(planned);
    int files = context.authorizedPaths().size();
    for (var entry : sorted.entrySet()) {
      var provider = providers.get(entry.getKey());
      if (provider == null) throw new IllegalStateException("授权工具缺少提供者：" + entry.getKey());
      if (entry.getValue() == null
          || entry.getValue() != ToolContracts.versionFor(entry.getKey(), files)
          || entry.getValue() != provider.contractVersion(files))
        throw new IllegalStateException("工具提供者的契约版本与装配计划不一致：" + entry.getKey());
      var spec =
          provider.create(
              new AgentToolProvider.Scope(
                  context.workspaceId(), context.authorizedPaths(), context.scenario()));
      if (spec == null) throw new IllegalStateException("工具提供者未声明工具：" + entry.getKey());
      if (toolkit.getTool(spec.name()) != null)
        throw new IllegalStateException("工具名称重复：" + spec.name());
      toolkit.registerAgentTool(
          new GatewayAgentTool<>(entry.getKey(), entry.getValue(), spec, context));
    }
    return toolkit;
  }
}
