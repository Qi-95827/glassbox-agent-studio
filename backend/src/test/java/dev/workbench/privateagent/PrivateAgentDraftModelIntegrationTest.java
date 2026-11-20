package dev.workbench.privateagent;

import static org.junit.jupiter.api.Assertions.*;

import dev.workbench.agent.AgentRoleRegistry;
import dev.workbench.app.WorkbenchApplication;
import dev.workbench.domain.AgentRole;
import dev.workbench.domain.ModelMode;
import dev.workbench.framework.agent.assembly.AgentAssemblyPlanner;
import dev.workbench.framework.agent.definition.AgentDefinitionRef;
import dev.workbench.infrastructure.model.TaskIdeaModel;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

/** 模型草稿路径与 Agent 模型策略的保存、版本和装配一致性。 */
@SpringBootTest(
    classes = WorkbenchApplication.class,
    properties = {
      "spring.datasource.url=jdbc:h2:mem:private_agent_draft_model;DB_CLOSE_DELAY=-1",
      "workbench.workspace-root=${java.io.tmpdir}/agent-workbench-private-agent-draft"
    })
@Import(PrivateAgentDraftModelIntegrationTest.StubIdeaModel.class)
class PrivateAgentDraftModelIntegrationTest {
  private static final String MODEL_JSON =
      """
      {
        "name": "模型生成检查 Agent",
        "description": "由模型生成的可编辑草稿",
        "responsibility": "核对前端可访问性并列出证据。",
        "outputRequirements": "列出实际文件、覆盖和未验证事项。",
        "permissionRole": "ANALYST",
        "recommendable": true,
        "modelStrategy": "FIXED",
        "modelMode": "deepseek",
        "skillRefs": ["S1"],
        "toolRefs": ["T1"],
        "openQuestions": ["是否需要真实浏览器检查"]
      }
      """;

  private static final java.util.concurrent.atomic.AtomicReference<String> SEEN_INSTRUCTIONS =
      new java.util.concurrent.atomic.AtomicReference<>();

  @TestConfiguration
  static class StubIdeaModel {
    @Bean
    @Primary
    TaskIdeaModel stubIdeaModel() {
      return new TaskIdeaModel() {
        @Override
        public String draft(String instructions, String idea) {
          SEEN_INSTRUCTIONS.set(instructions);
          return MODEL_JSON;
        }
      };
    }
  }

  @Autowired PrivateComponentService components;
  @Autowired AgentAssemblyPlanner assemblies;
  @Autowired AgentRoleRegistry roles;

  @Test
  void modelDraftIsStructuredAndFixedModelReachesAssembly() {
    var skill =
        components
            .publishSkill(
                components
                    .createSkill(
                        new PrivateComponentService.SkillRequest(
                            "草稿建议 Skill",
                            "模型可见 Skill",
                            null,
                            null,
                            null,
                            "# 指导"))
                    .id())
            .id();
    var tool =
        components
            .publishTool(
                components
                    .createTool(
                        new PrivateComponentService.ToolRequest(
                            "模型策略搜索", "只读搜索", "SEARCH", List.of(".jsx", ".tsx")))
                    .id())
            .id();

    components.updateSkill(
        skill,
        new PrivateComponentService.SkillRequest(
            "未发布 Skill 名称",
            "未发布摘要",
            null,
            null,
            null,
            "# 未发布正文"));
    components.updateTool(
        tool,
        new PrivateComponentService.ToolRequest(
            "未发布工具名称", "未发布工具说明", "SEARCH", List.of(".css")));

    var draft = components.draftFromIdea("检查前端可访问性，并给出覆盖范围", "MODEL");
    assertTrue(SEEN_INSTRUCTIONS.get().contains("草稿建议 Skill v1"));
    assertTrue(SEEN_INSTRUCTIONS.get().contains("模型可见 Skill"));
    assertTrue(SEEN_INSTRUCTIONS.get().contains("模型策略搜索 v1"));
    assertFalse(SEEN_INSTRUCTIONS.get().contains("未发布 Skill 名称"));
    assertFalse(SEEN_INSTRUCTIONS.get().contains("未发布工具说明"));
    assertEquals("模型生成检查 Agent", draft.name());
    assertEquals("ANALYST", draft.permissionRole());
    assertEquals("FIXED", draft.modelStrategy());
    assertEquals("deepseek", draft.modelMode());
    assertEquals(1, draft.openQuestions().size());
    assertEquals(1, draft.suggestedSkills().size());
    assertEquals(skill, draft.suggestedSkills().getFirst().id());
    assertEquals(1, draft.suggestedSkills().getFirst().version());
    assertEquals(1, draft.suggestedTools().size());
    assertEquals(tool, draft.suggestedTools().getFirst().id());
    assertEquals(1, draft.suggestedTools().getFirst().version());

    // 建议生成后组件发布 v2；Agent 仍固定草稿建议的 v1。
    components.publishSkill(skill);
    components.publishTool(tool);

    var local = components.draftFromIdea("检查前端可访问性，并给出覆盖范围", "LOCAL");
    assertEquals("INHERIT_TASK", local.modelStrategy());
    assertEquals(0, local.suggestedSkills().size());

    var created =
        components.createAgent(
            new PrivateComponentService.AgentRequest(
                draft.name(),
                draft.description(),
                draft.responsibility(),
                draft.outputRequirements(),
                draft.permissionRole(),
                draft.recommendable(),
                draft.modelMode(),
                draft.suggestedSkills(),
                draft.suggestedTools(),
                draft.modelStrategy()));
    assertEquals("FIXED", created.modelStrategy());
    var published = components.publishAgent(created.id());
    assertEquals("FIXED", published.modelStrategy());
    assertEquals("deepseek", published.modelMode());
    assertEquals("FIXED", components.agentVersions(created.id()).getFirst().modelStrategy());
    assertEquals("deepseek", components.agentVersions(created.id()).getFirst().modelMode());
    assertEquals(1, components.agentVersions(created.id()).getFirst().skills().getFirst().version());
    assertEquals(1, components.agentVersions(created.id()).getFirst().tools().getFirst().version());

    var assembly =
        assemblies.plan(
            AgentRole.ANALYST,
            new AgentDefinitionRef("pa-" + created.id(), 1),
            null,
            true,
            2,
            0,
            false);
    assertEquals(ModelMode.DEEPSEEK, assembly.modelOverride());
    assertTrue(assembly.privateTools().size() == 1);
    assertTrue(roles.forRole(AgentRole.ANALYST).tools().containsAll(assembly.definition().tools()));
  }
}
