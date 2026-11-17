package dev.workbench.agent;

import dev.workbench.domain.AgentRole;
import dev.workbench.domain.ToolKind;
import java.util.EnumSet;
import java.util.Set;

/** 角色声明可使用的能力上限；实际授权需与任务范围求交集。 */
record AgentRoleDefinition(
    AgentRole role, String name, int version, String systemPrompt, Set<ToolKind> tools) {
  AgentRoleDefinition {
    if (role == null
        || name == null
        || name.isBlank()
        || version < 1
        || systemPrompt == null
        || systemPrompt.isBlank()
        || tools == null) throw new IllegalArgumentException("Agent 角色定义不完整。");
    tools = Set.copyOf(tools);
  }

  /** 用户授权和角色声明共同限制最终工具集，任何一方都不能单独扩大权限。 */
  Set<ToolKind> authorizedTools(boolean workspaceAvailable, boolean writeEnabled) {
    if (writeEnabled && !workspaceAvailable) throw new IllegalArgumentException("没有工作区时不能授予写入权限。");
    EnumSet<ToolKind> result = EnumSet.noneOf(ToolKind.class);
    result.addAll(tools);
    if (!workspaceAvailable) {
      result.remove(ToolKind.WORKSPACE_READ);
      result.remove(ToolKind.WORKSPACE_SEARCH);
      result.remove(ToolKind.WORKSPACE_WRITE);
    } else if (!writeEnabled) {
      result.remove(ToolKind.WORKSPACE_WRITE);
    }
    return Set.copyOf(result);
  }
}
