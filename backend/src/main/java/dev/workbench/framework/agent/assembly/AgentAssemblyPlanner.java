package dev.workbench.framework.agent.assembly;

import dev.workbench.agent.AgentRoleRegistry;
import dev.workbench.domain.AgentRole;
import dev.workbench.domain.ToolKind;
import dev.workbench.framework.agent.definition.AgentDefinition;
import dev.workbench.framework.agent.definition.AgentDefinitionRef;
import dev.workbench.framework.agent.definition.AgentDefinitionRegistry;
import dev.workbench.framework.skill.SkillDefinition;
import dev.workbench.framework.skill.SkillRegistry;
import dev.workbench.framework.tool.ToolContracts;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** 创建不可变装配计划；实际注册和调用仍由执行器及运行控制检查。 */
@Component
public class AgentAssemblyPlanner {
  private final AgentRoleRegistry roles;
  private final AgentDefinitionRegistry definitions;
  private final SkillRegistry skills;

  @Autowired
  public AgentAssemblyPlanner(
      AgentRoleRegistry roles, AgentDefinitionRegistry definitions, SkillRegistry skills) {
    this.roles = roles;
    this.definitions = definitions;
    this.skills = skills;
  }

  /** 包内兼容构造：用于只包含旧内置角色的独立验证。 */
  public AgentAssemblyPlanner(AgentRoleRegistry roles, SkillRegistry skills) {
    this(roles, new AgentDefinitionRegistry(roles, skills, List.of()), skills);
  }

  public Assembly plan(AgentRole role, boolean hasWorkspace, int fileCount, boolean writeEnabled) {
    return plan(role, null, hasWorkspace, fileCount, writeEnabled);
  }

  public Assembly plan(
      AgentRole role,
      AgentDefinitionRef requested,
      boolean hasWorkspace,
      int fileCount,
      boolean writeEnabled) {
    return plan(role, requested, null, hasWorkspace, fileCount, writeEnabled);
  }

  public Assembly plan(
      AgentRole role,
      AgentDefinitionRef requested,
      String expectedSha256,
      boolean hasWorkspace,
      int fileCount,
      boolean writeEnabled) {
    if (fileCount < 0
        || fileCount > 20
        || (hasWorkspace && fileCount == 0)
        || (!hasWorkspace && fileCount != 0)) throw new IllegalArgumentException("工作区授权文件数量无效。");
    var ref = requested == null ? definitions.forLegacyRole(role) : requested;
    var definition = definitions.resolve(ref);
    if (definition.permissionProfile() != role) {
      throw new IllegalArgumentException("Agent 定义与运行的权限配置不一致。");
    }
    String fingerprint = definitions.fingerprint(ref);
    if (expectedSha256 != null && !expectedSha256.equals(fingerprint)) {
      throw new IllegalStateException("Agent 定义内容与计划摘要不一致，不能执行。");
    }
    var authorized = roles.forRole(role).authorizedTools(hasWorkspace, writeEnabled);
    var versions = new EnumMap<ToolKind, Integer>(ToolKind.class);
    for (var tool : definition.tools()) {
      if (authorized.contains(tool)) {
        int version = ToolContracts.versionFor(tool, fileCount);
        versions.put(tool, version);
      }
    }
    return new Assembly(definition, skills.resolve(definition.skills()), versions, fingerprint);
  }

  /** 技能指导与工具契约是装配计划，不表示已注册、已调用或执行成功。 */
  public record Assembly(
      AgentDefinition definition,
      List<SkillDefinition> skills,
      Map<ToolKind, Integer> toolVersions,
      String definitionSha256) {
    public Assembly {
      skills = List.copyOf(skills);
      toolVersions = Map.copyOf(toolVersions);
    }
  }
}
