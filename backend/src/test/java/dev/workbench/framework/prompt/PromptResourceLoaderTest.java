package dev.workbench.framework.prompt;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** 验证外置在 resources/prompts/ 下的标准 Agent Prompt、Skill 与工具契约描述均可正常加载。 */
class PromptResourceLoaderTest {

  @Test
  void loadsStandardStructuredAgentPrompts() {
    for (String file : List.of("agents/analyst.md", "agents/coder.md", "agents/reviewer.md")) {
      String text = PromptResourceLoader.load(file);
      assertTrue(text.contains("# 角色定位"), file + " 缺少角色定位");
      assertTrue(text.contains("## 核心职责"), file + " 缺少核心职责");
      assertTrue(text.contains("## 工作原则与安全边界"), file + " 缺少安全边界");
      assertTrue(text.contains("## 输出规范"), file + " 缺少输出规范");
      assertTrue(text.contains("## 输出示例"), file + " 缺少输出示例");
    }
  }

  @Test
  void loadsSkillsAndPlanningPrompts() {
    assertFalse(PromptResourceLoader.load("skills/analyze-evidence.md").isBlank());
    assertFalse(PromptResourceLoader.load("skills/authorized-edit.md").isBlank());
    assertFalse(PromptResourceLoader.load("skills/review-evidence.md").isBlank());
    assertTrue(PromptResourceLoader.load("planning/plan-candidate.md").contains("schemaVersion"));
    assertTrue(PromptResourceLoader.load("planning/task-idea.md").contains("entryKind"));
  }

  @Test
  void loadsAllBuiltInToolDescriptionsAndParameters() {
    assertFalse(PromptResourceLoader.tool("simulated_inspection").description().isBlank());
    assertFalse(PromptResourceLoader.tool("workspace_read_single").description().isBlank());
    assertFalse(PromptResourceLoader.tool("workspace_read_scoped").parameter("path").isBlank());
    assertFalse(
        PromptResourceLoader.tool("workspace_write_single").parameter("expectedSha256").isBlank());
    assertFalse(
        PromptResourceLoader.tool("workspace_write_scoped").parameter("content").isBlank());
    assertFalse(PromptResourceLoader.tool("workspace_search").parameter("query").isBlank());
  }

  private static final class List {
    static java.util.List<String> of(String... items) {
      return java.util.List.of(items);
    }
  }
}
