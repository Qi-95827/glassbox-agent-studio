package dev.workbench.agent;

import static org.junit.jupiter.api.Assertions.*;

import dev.workbench.domain.AgentRole;
import dev.workbench.domain.ToolKind;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 角色能力与用户授权求交集，不能由单方扩大工具范围。 */
class AgentRoleDefinitionTest {
  @Test
  void toolsAreLimitedByBothRoleAndTaskGrant() {
    var registry = new AgentRoleRegistry();
    var analyst = registry.forRole(AgentRole.ANALYST);
    var coder = registry.forRole(AgentRole.CODER);
    var reviewer = registry.forRole(AgentRole.REVIEWER);
    assertEquals(AgentRole.ANALYST.version(), analyst.version());
    assertEquals(AgentRole.CODER.version(), coder.version());
    assertEquals(AgentRole.REVIEWER.version(), reviewer.version());
    assertEquals(Set.of(ToolKind.SIMULATED_INSPECTION), analyst.authorizedTools(false, false));
    assertEquals(
        Set.of(ToolKind.SIMULATED_INSPECTION, ToolKind.WORKSPACE_READ),
        analyst.authorizedTools(true, false));
    assertEquals(
        Set.of(ToolKind.SIMULATED_INSPECTION, ToolKind.WORKSPACE_READ),
        analyst.authorizedTools(true, true));
    assertEquals(Set.of(ToolKind.WORKSPACE_READ), coder.authorizedTools(true, false));
    assertEquals(
        Set.of(ToolKind.WORKSPACE_READ, ToolKind.WORKSPACE_WRITE),
        coder.authorizedTools(true, true));
    assertTrue(reviewer.authorizedTools(true, true).isEmpty());
    assertThrows(IllegalArgumentException.class, () -> analyst.authorizedTools(false, true));
    assertThrows(UnsupportedOperationException.class, () -> analyst.tools().clear());
  }

  @Test
  void roleRegistryRejectsIncompleteOrInconsistentDefinitions() {
    var defaults = new AgentRoleRegistry();
    var definitions = new java.util.EnumMap<AgentRole, AgentRoleDefinition>(AgentRole.class);
    for (var role : AgentRole.values()) definitions.put(role, defaults.forRole(role));
    var snapshot = new AgentRoleRegistry(definitions);
    definitions.remove(AgentRole.REVIEWER);
    assertNotNull(snapshot.forRole(AgentRole.REVIEWER));
    assertThrows(IllegalArgumentException.class, () -> new AgentRoleRegistry(definitions));
    definitions.put(
        AgentRole.REVIEWER,
        new AgentRoleDefinition(AgentRole.REVIEWER, "review", 99, "职责", Set.of()));
    assertThrows(IllegalArgumentException.class, () -> new AgentRoleRegistry(definitions));
    definitions.put(AgentRole.REVIEWER, defaults.forRole(AgentRole.REVIEWER));
    definitions.put(AgentRole.ANALYST, defaults.forRole(AgentRole.CODER));
    assertThrows(IllegalArgumentException.class, () -> new AgentRoleRegistry(definitions));
    assertThrows(
        IllegalArgumentException.class,
        () -> new AgentRoleDefinition(AgentRole.ANALYST, " ", 1, "职责", Set.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new AgentRoleDefinition(AgentRole.ANALYST, "role", 0, "职责", Set.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new AgentRoleDefinition(AgentRole.ANALYST, "role", 1, " ", Set.of()));
  }
}
