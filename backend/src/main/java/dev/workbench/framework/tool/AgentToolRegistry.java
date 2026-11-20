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
    int readFiles = context.authorizedPaths().size();
    int writeFiles = context.writePaths().size();
    for (var entry : sorted.entrySet()) {
      var provider = providers.get(entry.getKey());
      if (provider == null) throw new IllegalStateException("授权工具缺少提供者：" + entry.getKey());
      // 写入工具的契约只取决于节点写入集合；读取和搜索使用节点读取集合。
      boolean writes = entry.getKey() == ToolKind.WORKSPACE_WRITE;
      int files = writes ? writeFiles : readFiles;
      if (entry.getValue() == null
          || entry.getValue() != ToolContracts.versionFor(entry.getKey(), files)
          || entry.getValue() != provider.contractVersion(files))
        throw new IllegalStateException("工具提供者的契约版本与装配计划不一致：" + entry.getKey());
      var spec =
          provider.create(
              new AgentToolProvider.Scope(
                  context.workspaceId(),
                  writes ? context.writePaths() : context.authorizedPaths(),
                  context.scenario()));
      if (spec == null) throw new IllegalStateException("工具提供者未声明工具：" + entry.getKey());
      if (toolkit.getTool(spec.name()) != null)
        throw new IllegalStateException("工具名称重复：" + spec.name());
      toolkit.registerAgentTool(
          new GatewayAgentTool<>(entry.getKey(), entry.getValue(), spec, context));
    }
    return toolkit;
  }

  /**
   * 注册私有只读工具：执行器仍是受信读取/搜索，但模型看到的名称、描述和文件扩展名来自固定版本。
   *
   * <p>文件范围先与节点授权求交，再按扩展名收窄；收窄后为空时拒绝装配，而不是退化成全量授权。
   */
  public Toolkit assemblePrivate(
      java.util.List<dev.workbench.privateagent.PrivateComponentService.PinnedTool> planned,
      AgentToolContext context) {
    var toolkit = new Toolkit();
    for (var tool : planned) {
      ToolKind kind =
          "SEARCH".equals(tool.executorKind())
              ? ToolKind.WORKSPACE_SEARCH
              : ToolKind.WORKSPACE_READ;
      var provider = providers.get(kind);
      if (provider == null) throw new IllegalStateException("私有工具缺少受信执行器：" + kind);
      var filtered =
          context.authorizedPaths().stream()
              .filter(
                  path ->
                      dev.workbench.privateagent.PrivateAgentCatalog.matchesExtensions(
                          path, tool.fileExtensions()))
              .toList();
      if (filtered.isEmpty())
        throw new IllegalStateException(
            "私有工具「"
                + tool.name()
                + "」没有匹配当前节点授权的文件（允许："
                + String.join(", ", tool.fileExtensions())
                + "）。");
      var scopedContext =
          new AgentToolContext(
              context.json(),
              context.workspaceId(),
              filtered,
              context.scenario(),
              context.runtime(),
              context.failure(),
              java.util.List.of());
      var original =
          provider.create(
              new AgentToolProvider.Scope(context.workspaceId(), filtered, context.scenario()));
      if (original == null) throw new IllegalStateException("私有工具执行器未声明工具。");
      var spec = privateSpec(original, tool);
      if (toolkit.getTool(spec.name()) != null)
        throw new IllegalStateException("私有工具名称重复：" + spec.name());
      int version = ToolContracts.versionFor(kind, context.authorizedPaths().size());
      toolkit.registerAgentTool(new GatewayAgentTool<>(kind, version, spec, scopedContext));
    }
    return toolkit;
  }

  private static <T> ToolSpecification<T> privateSpec(
      ToolSpecification<T> original,
      dev.workbench.privateagent.PrivateComponentService.PinnedTool tool) {
    return new ToolSpecification<>(
        tool.toolName(),
        tool.name()
            + " 只搜索或读取以下扩展名："
            + String.join(", ", tool.fileExtensions())
            + "；仍受当前任务与节点授权文件限制。",
        original.parameters(),
        java.util.stream.Stream.concat(
                original.fingerprintPrefix().stream(),
                java.util.stream.Stream.of(tool.id(), "v" + tool.version()))
            .toList(),
        original.startMessage(),
        original.validate(),
        original.action(),
        original.summarize(),
        original.resourceVersion());
  }
}
