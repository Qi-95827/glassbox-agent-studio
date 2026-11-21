package dev.workbench.framework.agent.definition;

import dev.workbench.domain.AgentRole;
import dev.workbench.domain.ModelMode;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 内置与私有 Agent 定义的统一解析入口。
 *
 * <p>私有引用固定版本，不读取草稿或最新版本；归属与发布状态由调用方在计划、确认和执行阶段复核。
 * 内置定义继续走原目录，不把用户数据注册进全局 Map。具体私有目录通过
 * {@link PrivateAgentDefinitionSource} 端口注入，framework 不依赖业务实现。
 */
@Component
public class AgentDefinitionCatalog {
  private final AgentDefinitionRegistry builtins;
  private final PrivateAgentDefinitionSource privateAgents;

  @Autowired
  public AgentDefinitionCatalog(
      AgentDefinitionRegistry builtins, PrivateAgentDefinitionSource privateAgents) {
    this.builtins = builtins;
    this.privateAgents = privateAgents;
  }

  /** 只含内置定义的兼容构造，供独立单元验证使用。 */
  public AgentDefinitionCatalog(AgentDefinitionRegistry builtins) {
    this(builtins, null);
  }

  public AgentDefinition resolve(AgentDefinitionRef ref) {
    return isPrivate(ref) ? requirePrivate().definition(ref) : builtins.resolve(ref);
  }

  public String fingerprint(AgentDefinitionRef ref) {
    return isPrivate(ref) ? requirePrivate().fingerprint(ref) : builtins.fingerprint(ref);
  }

  public AgentDefinitionRef forLegacyRole(AgentRole role) {
    return builtins.forLegacyRole(role);
  }

  public boolean isPrivate(AgentDefinitionRef ref) {
    return privateAgents != null && privateAgents.isPrivateReference(ref);
  }

  /** 私有定义声明的 Skill 正文和工具绑定；内置定义返回空。 */
  public PrivateAgentDefinitionSource.Components privateComponents(AgentDefinitionRef ref) {
    if (!isPrivate(ref)) return PrivateAgentDefinitionSource.Components.empty();
    return requirePrivate().components(ref);
  }

  /** 固定模型策略；继承任务时为空。 */
  public Optional<ModelMode> fixedModelMode(AgentDefinitionRef ref) {
    return isPrivate(ref) ? requirePrivate().fixedModelMode(ref) : Optional.empty();
  }

  private PrivateAgentDefinitionSource requirePrivate() {
    if (privateAgents == null) throw new IllegalArgumentException("当前目录没有加载私有 Agent 定义。");
    return privateAgents;
  }
}
