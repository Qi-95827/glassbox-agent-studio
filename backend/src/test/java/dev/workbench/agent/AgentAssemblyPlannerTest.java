package dev.workbench.agent;

import static org.junit.jupiter.api.Assertions.*;

import dev.workbench.domain.AgentRole;
import dev.workbench.domain.ToolKind;
import dev.workbench.skill.SkillId;
import dev.workbench.skill.SkillRegistry;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 装配计划必须同时满足角色与任务授权，并固定实际工具版本。 */
class AgentAssemblyPlannerTest {
  private final AgentAssemblyPlanner planner =
      new AgentAssemblyPlanner(new AgentRoleRegistry(), new SkillRegistry());

  @Test
  void computesAuthorizedToolsSkillsAndProtocolVersions() {
    var isolated = planner.plan(AgentRole.ANALYST, false, 0, false);
    assertEquals(Map.of(ToolKind.SIMULATED_INSPECTION, 1), isolated.toolVersions());
    assertEquals(SkillId.ANALYZE_EVIDENCE, isolated.skills().getFirst().id());
    var readOnly = planner.plan(AgentRole.CODER, true, 1, false);
    assertEquals(
        Map.of(ToolKind.WORKSPACE_READ, 1, ToolKind.WORKSPACE_SEARCH, 1), readOnly.toolVersions());
    var multiFile = planner.plan(AgentRole.CODER, true, 2, true);
    assertEquals(
        Map.of(
            ToolKind.WORKSPACE_READ, 2, ToolKind.WORKSPACE_SEARCH, 1, ToolKind.WORKSPACE_WRITE, 2),
        multiFile.toolVersions());
    assertTrue(planner.plan(AgentRole.REVIEWER, true, 2, true).toolVersions().isEmpty());
    assertThrows(UnsupportedOperationException.class, () -> multiFile.toolVersions().clear());
    assertThrows(UnsupportedOperationException.class, () -> multiFile.skills().clear());
  }

  @Test
  void rejectsInconsistentWorkspaceAndAuthorization() {
    assertThrows(
        IllegalArgumentException.class, () -> planner.plan(AgentRole.ANALYST, true, 0, false));
    assertThrows(
        IllegalArgumentException.class, () -> planner.plan(AgentRole.ANALYST, false, 1, false));
    assertThrows(
        IllegalArgumentException.class, () -> planner.plan(AgentRole.CODER, true, 21, true));
    assertThrows(
        IllegalArgumentException.class, () -> planner.plan(AgentRole.CODER, false, 0, true));
    assertThrows(
        IllegalArgumentException.class,
        () -> dev.workbench.tool.ToolContracts.versionFor(ToolKind.WORKSPACE_READ, 0));
    assertThrows(
        IllegalArgumentException.class, () -> dev.workbench.tool.ToolContracts.versionFor(null, 1));
  }
}
