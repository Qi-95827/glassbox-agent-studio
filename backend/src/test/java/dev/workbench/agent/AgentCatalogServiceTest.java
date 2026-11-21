package dev.workbench.agent;

import static org.junit.jupiter.api.Assertions.*;

import dev.workbench.domain.AgentRole;
import dev.workbench.domain.ToolKind;
import dev.workbench.framework.skill.SkillRegistry;
import java.util.Set;
import org.junit.jupiter.api.Test;
import dev.workbench.framework.agent.definition.AgentRoleRegistry;

/** 目录必须来自实际装配定义，声明不等于任务权限。 */
class AgentCatalogServiceTest {
  @Test
  void describesCurrentRoleAndSkillDefinitions() {
    var registry = new SkillRegistry();
    var roleRegistry = new AgentRoleRegistry();
    var roles = new AgentCatalogService(registry, roleRegistry).roles();
    assertEquals(AgentRole.values().length, roles.size());
    for (var role : roles) {
      var actual = roleRegistry.forRole(role.role());
      assertEquals(role.version(), actual.version());
      assertEquals(actual.tools(), Set.copyOf(role.declaredTools()));
      assertEquals(
          registry.forRole(role.role()).getFirst().contentSha256(),
          role.skills().getFirst().contentSha256());
    }
    var analyst =
        roles.stream().filter(r -> r.role() == AgentRole.ANALYST).findFirst().orElseThrow();
    assertTrue(analyst.declaredTools().contains(ToolKind.WORKSPACE_READ));
    assertFalse(
        roleRegistry
            .forRole(AgentRole.ANALYST)
            .authorizedTools(false, false)
            .contains(ToolKind.WORKSPACE_READ));
    var reviewer =
        roles.stream().filter(r -> r.role() == AgentRole.REVIEWER).findFirst().orElseThrow();
    assertTrue(reviewer.declaredTools().isEmpty());
  }
}
