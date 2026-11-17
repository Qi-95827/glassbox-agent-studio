package dev.workbench.framework.agent.definition;

import dev.workbench.domain.AgentRole;
import dev.workbench.domain.ToolKind;
import dev.workbench.framework.skill.SkillId;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** 受信代码提供的 Agent 装配声明；声明工具不等于获得任务授权。 */
public record AgentDefinition(
    AgentDefinitionRef ref,
    String name,
    String systemPrompt,
    AgentRole permissionProfile,
    List<ToolKind> tools,
    List<SkillId> skills) {
  public AgentDefinition {
    Objects.requireNonNull(ref, "Agent 定义引用不能为空");
    Objects.requireNonNull(permissionProfile, "旧权限配置不能为空");
    if (name == null || name.isBlank() || systemPrompt == null || systemPrompt.isBlank()) {
      throw new IllegalArgumentException("Agent 名称或职责不能为空。");
    }
    if (tools == null
        || skills == null
        || tools.stream().anyMatch(Objects::isNull)
        || skills.stream().anyMatch(Objects::isNull)
        || new HashSet<>(tools).size() != tools.size()
        || new HashSet<>(skills).size() != skills.size()) {
      throw new IllegalArgumentException("Agent 工具或技能清单为空、包含空项或重复项。");
    }
    tools = List.copyOf(tools);
    skills = List.copyOf(skills);
  }
}
