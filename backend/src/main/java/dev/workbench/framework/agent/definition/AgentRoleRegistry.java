package dev.workbench.framework.agent.definition;

import dev.workbench.domain.AgentRole;
import dev.workbench.domain.ToolKind;
import dev.workbench.framework.prompt.PromptResourceLoader;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;

/** 服务端受控的内置角色目录，运行装配和用户目录共用。 */
@Component
public class AgentRoleRegistry {
  private final Map<AgentRole, AgentRoleDefinition> definitions;

  public AgentRoleRegistry() {
    this(builtInDefinitions());
  }

  /** 所有内置角色必须有完整定义和匹配的版本。 */
  public AgentRoleRegistry(Map<AgentRole, AgentRoleDefinition> definitions) {
    if (definitions == null) throw new IllegalArgumentException("角色定义不能为空。");
    var checked = new EnumMap<AgentRole, AgentRoleDefinition>(AgentRole.class);
    for (AgentRole role : AgentRole.values()) {
      var definition = definitions.get(role);
      if (definition == null || definition.role() != role || definition.version() != role.version())
        throw new IllegalArgumentException("角色定义缺失或版本不匹配：" + role);
      checked.put(role, definition);
    }
    if (definitions.size() != checked.size()) throw new IllegalArgumentException("角色目录包含未知角色。");
    this.definitions = Map.copyOf(checked);
  }

  public AgentRoleDefinition forRole(AgentRole role) {
    var definition = definitions.get(Objects.requireNonNull(role, "角色不能为空"));
    if (definition == null) throw new IllegalArgumentException("角色定义不存在。");
    return definition;
  }

  /** 按任务语言返回本地化系统 Prompt；定义摘要与权限仍以固定中文定义为准。 */
  public String systemPrompt(AgentRole role, String locale) {
    String relativePath =
        switch (Objects.requireNonNull(role, "角色不能为空")) {
          case ANALYST -> "agents/analyst.md";
          case CODER -> "agents/coder.md";
          case REVIEWER -> "agents/reviewer.md";
        };
    return PromptResourceLoader.load(
            dev.workbench.framework.prompt.PromptResourceLoader.normalizeLocale(locale),
            relativePath)
        + "\n\n"
        + PromptResourceLoader.outputLanguageDirective(locale);
  }

  private static Map<AgentRole, AgentRoleDefinition> builtInDefinitions() {
    var result = new EnumMap<AgentRole, AgentRoleDefinition>(AgentRole.class);
    for (AgentRole role : AgentRole.values()) result.put(role, builtInDefinition(role));
    return result;
  }

  private static AgentRoleDefinition builtInDefinition(AgentRole role) {
    return switch (role) {
      case ANALYST ->
          new AgentRoleDefinition(
              role,
              "analysis-agent",
              role.version(),
              PromptResourceLoader.load("agents/analyst.md"),
              Set.of(
                  ToolKind.SIMULATED_INSPECTION,
                  ToolKind.WORKSPACE_READ,
                  ToolKind.WORKSPACE_SEARCH));
      case CODER ->
          new AgentRoleDefinition(
              role,
              "coding-agent",
              role.version(),
              PromptResourceLoader.load("agents/coder.md"),
              Set.of(
                  ToolKind.WORKSPACE_READ,
                  ToolKind.WORKSPACE_SEARCH,
                  ToolKind.WORKSPACE_WRITE));
      case REVIEWER ->
          new AgentRoleDefinition(
              role,
              "review-agent",
              role.version(),
              PromptResourceLoader.load("agents/reviewer.md"),
              Set.of());
    };
  }
}
