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
        "6b36011207aa95035cc31fc4c2af8fe4d945d15c273d14cd23ae72df515ad6e5",
        analysis.contentSha256());
    assertEquals(
        "897f4828e422ab24d9db3cefd33aafaef8f24760df7a5a5b5b3287e2ee6a1cfd", coding.contentSha256());
    assertEquals(
        "c6a53ee69a370fac294d1eeea6adc681fb7fb3411554ed1204562e123c106424", review.contentSha256());
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
