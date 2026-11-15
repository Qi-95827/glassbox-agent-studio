package dev.workbench.agent;

import dev.workbench.domain.AgentRole;
import dev.workbench.domain.ToolKind;
import dev.workbench.skill.SkillDefinition;
import dev.workbench.skill.SkillRegistry;
import dev.workbench.tool.ToolContracts;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** 创建不可变装配计划；实际注册和调用仍由执行器及运行控制检查。 */
@Component
public class AgentAssemblyPlanner {
  private final AgentRoleRegistry roles;
  private final SkillRegistry skills;

  public AgentAssemblyPlanner(AgentRoleRegistry roles, SkillRegistry skills) {
    this.roles = roles;
    this.skills = skills;
  }

  Assembly plan(AgentRole role, boolean hasWorkspace, int fileCount, boolean writeEnabled) {
    if (fileCount < 0
        || fileCount > 20
        || (hasWorkspace && fileCount == 0)
        || (!hasWorkspace && fileCount != 0)) throw new IllegalArgumentException("工作区授权文件数量无效。");
    var definition = roles.forRole(role);
    var authorized = definition.authorizedTools(hasWorkspace, writeEnabled);
    var versions = new EnumMap<ToolKind, Integer>(ToolKind.class);
    for (var tool : authorized) {
      int version = ToolContracts.versionFor(tool, fileCount);
      versions.put(tool, version);
    }
    return new Assembly(definition, skills.forRole(role), versions);
  }

  /** 技能指导与工具契约是装配计划，不表示已注册、已调用或执行成功。 */
  record Assembly(
      AgentRoleDefinition definition,
      List<SkillDefinition> skills,
      Map<ToolKind, Integer> toolVersions) {
    Assembly {
      skills = List.copyOf(skills);
      toolVersions = Map.copyOf(toolVersions);
    }
  }
}
