package dev.workbench.framework.skill;

import static org.junit.jupiter.api.Assertions.*;

import dev.workbench.domain.AgentRole;
import org.junit.jupiter.api.Test;

/** 技能版本与摘要绑定，方法文本改变时必须显式更新测试和版本。 */
class SkillRegistryTest {
  @Test
  void builtInSkillsAreAssignedByRoleAndHaveStableDigests() {
    var registry = new SkillRegistry();
    var analysis = registry.forRole(AgentRole.ANALYST).getFirst();
    var coding = registry.forRole(AgentRole.CODER).getFirst();
    var review = registry.forRole(AgentRole.REVIEWER).getFirst();
    assertEquals(SkillId.ANALYZE_EVIDENCE, analysis.id());
    assertEquals(SkillId.AUTHORIZED_EDIT, coding.id());
    assertEquals(SkillId.REVIEW_EVIDENCE, review.id());
    assertEquals(1, analysis.version());
    assertEquals(
        "d6923a24aae310d14bc2d0d7a0f1c8b25ad6a37c463084732570f2d7cbee96c9",
        analysis.contentSha256());
    assertEquals(
        "04dfcf9aa557322bf824f76b3e36a38f8dbe9472459a3da15ad1e8c52261ecd5", coding.contentSha256());
    assertEquals(
        "ed02780c7e9c5723d101eff98fa829cef0e92b0f141e9907ab87d780dad83955", review.contentSha256());
  }

  @Test
  void compositionPreservesOrderAndRejectsInvalidBindings() {
    var builtins = new SkillRegistry();
    var analysis = builtins.forRole(AgentRole.ANALYST).getFirst();
    var edit = builtins.forRole(AgentRole.CODER).getFirst();
    var definitions = new java.util.EnumMap<SkillId, SkillDefinition>(SkillId.class);
    definitions.put(analysis.id(), analysis);
    definitions.put(edit.id(), edit);
    var analystSkills = new java.util.ArrayList<>(java.util.List.of(edit.id(), analysis.id()));
    var bindings = new java.util.EnumMap<AgentRole, java.util.List<SkillId>>(AgentRole.class);
    bindings.put(AgentRole.ANALYST, analystSkills);
    bindings.put(AgentRole.CODER, java.util.List.of(edit.id()));
    bindings.put(AgentRole.REVIEWER, java.util.List.of());
    var composed = new SkillRegistry(definitions, bindings);
    analystSkills.clear();
    definitions.clear();
    assertEquals(
        java.util.List.of(SkillId.AUTHORIZED_EDIT, SkillId.ANALYZE_EVIDENCE),
        composed.forRole(AgentRole.ANALYST).stream().map(SkillDefinition::id).toList());
    assertTrue(composed.forRole(AgentRole.REVIEWER).isEmpty());

    var catalog = java.util.Map.of(analysis.id(), analysis);
    bindings.put(AgentRole.ANALYST, java.util.List.of(analysis.id(), analysis.id()));
    assertThrows(IllegalArgumentException.class, () -> new SkillRegistry(catalog, bindings));
    bindings.put(AgentRole.ANALYST, java.util.List.of(analysis.id()));
    assertThrows(IllegalArgumentException.class, () -> new SkillRegistry(catalog, bindings));
    bindings.put(AgentRole.CODER, java.util.List.of());
    bindings.remove(AgentRole.REVIEWER);
    assertThrows(IllegalArgumentException.class, () -> new SkillRegistry(catalog, bindings));
    bindings.put(AgentRole.REVIEWER, java.util.List.of());
    assertThrows(
        IllegalArgumentException.class,
        () -> new SkillRegistry(java.util.Map.of(SkillId.AUTHORIZED_EDIT, analysis), bindings));
  }
}
