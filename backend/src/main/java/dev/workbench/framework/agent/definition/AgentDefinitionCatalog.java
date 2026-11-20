package dev.workbench.framework.agent.definition;

import dev.workbench.domain.AgentRole;
import dev.workbench.privateagent.PrivateAgentCatalog;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 内置与私有 Agent 定义的统一解析入口。
 *
 * <p>私有引用固定版本，不读取草稿或最新版本；归属与发布状态由调用方在计划、确认和执行阶段复核。 内置定义继续走原目录，不把用户数据注册进全局 Map。
 */
@Component
public class AgentDefinitionCatalog {
  private final AgentDefinitionRegistry builtins;
  private final PrivateAgentCatalog privateAgents;

  @Autowired
  public AgentDefinitionCatalog(
      AgentDefinitionRegistry builtins, PrivateAgentCatalog privateAgents) {
    this.builtins = builtins;
    this.privateAgents = privateAgents;
  }

  /** 只含内置定义的兼容构造，供独立单元验证使用。 */
  public AgentDefinitionCatalog(AgentDefinitionRegistry builtins) {
    this(builtins, null);
  }

  public AgentDefinition resolve(AgentDefinitionRef ref) {
    return PrivateAgentCatalog.isPrivate(ref)
        ? requirePrivate().definition(ref)
        : builtins.resolve(ref);
  }

  public String fingerprint(AgentDefinitionRef ref) {
    return PrivateAgentCatalog.isPrivate(ref)
        ? requirePrivate().fingerprint(ref)
        : builtins.fingerprint(ref);
  }

  public AgentDefinitionRef forLegacyRole(AgentRole role) {
    return builtins.forLegacyRole(role);
  }

  public boolean isPrivate(AgentDefinitionRef ref) {
    return PrivateAgentCatalog.isPrivate(ref);
  }

  /** 私有定义声明的 Skill 正文和工具绑定；内置定义返回空。 */
  public PrivateAgentCatalog.Components privateComponents(AgentDefinitionRef ref) {
    if (!isPrivate(ref)) return new PrivateAgentCatalog.Components(List.of(), List.of());
    return requirePrivate().components(requirePrivate().version(ref));
  }

  public PrivateAgentCatalog privateAgents() {
    return requirePrivate();
  }

  private PrivateAgentCatalog requirePrivate() {
    if (privateAgents == null) throw new IllegalArgumentException("当前目录没有加载私有 Agent 定义。");
    return privateAgents;
  }
}
