package dev.workbench.framework.agent.definition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.workbench.domain.AgentRole;
import dev.workbench.domain.ToolKind;
import dev.workbench.framework.tool.ToolContracts;
import dev.workbench.framework.tool.ToolEffect;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 新增只读工具的授权规则。
 *
 * <p>这两个工具都不扩大写权限：知识库检索按工作区隔离，上下文产物回读按运行隔离。
 * 内置角色的默认工具集保持原样（避免悄悄扩大既有 Agent 的权限面），
 * 但任何声明了它们的角色/Agent 定义都必须遵守下面的交集规则。
 */
class RoleToolGrantTest {
  private static AgentRoleDefinition roleWith(ToolKind... tools) {
    return new AgentRoleDefinition(
        AgentRole.ANALYST, "custom-analyst", AgentRole.ANALYST.version(), "system", Set.of(tools));
  }

  @Test
  void bothNewToolsAreGrantedWhenTheRoleDeclaresThem() {
    var definition = roleWith(ToolKind.KNOWLEDGE_SEARCH, ToolKind.CONTEXT_ARTIFACT_READ);
    var tools = definition.authorizedTools(true, false);
    assertTrue(tools.contains(ToolKind.KNOWLEDGE_SEARCH));
    assertTrue(tools.contains(ToolKind.CONTEXT_ARTIFACT_READ));
  }

  @Test
  void withoutWorkspaceKnowledgeSearchIsDroppedButArtifactReadRemains() {
    var definition = roleWith(ToolKind.KNOWLEDGE_SEARCH, ToolKind.CONTEXT_ARTIFACT_READ);
    var tools = definition.authorizedTools(false, false);
    // 知识库按工作区隔离：没有工作区就没有语料，装配了只会命中空集。
    assertFalse(tools.contains(ToolKind.KNOWLEDGE_SEARCH));
    // 产物回读只需要运行归属，与工作区无关。
    assertTrue(tools.contains(ToolKind.CONTEXT_ARTIFACT_READ));
  }

  @Test
  void reviewerKeepsNoToolsByDefault() {
    // 内置审查角色不声明任何工具：它只看证据，不主动取数。
    assertTrue(new AgentRoleRegistry().forRole(AgentRole.REVIEWER).tools().isEmpty());
  }

  @Test
  void newToolsAreReadOnlyAndVersionedWithoutFileAuthorization() {
    assertEquals(ToolEffect.READ_ONLY, ToolContracts.effectFor(ToolKind.KNOWLEDGE_SEARCH));
    assertEquals(ToolEffect.READ_ONLY, ToolContracts.effectFor(ToolKind.CONTEXT_ARTIFACT_READ));
    // 作用域只读工具的版本与授权文件数量无关，0 个文件也必须能装配。
    assertEquals(
        ToolContracts.READ_SCOPE_VERSION, ToolContracts.versionFor(ToolKind.KNOWLEDGE_SEARCH, 0));
    assertEquals(
        ToolContracts.READ_SCOPE_VERSION,
        ToolContracts.versionFor(ToolKind.CONTEXT_ARTIFACT_READ, 0));
  }

  @Test
  void writeToolsStillRequireAuthorizedFiles() {
    assertThrows(
        IllegalArgumentException.class, () -> ToolContracts.versionFor(ToolKind.WORKSPACE_WRITE, 0));
  }
}
