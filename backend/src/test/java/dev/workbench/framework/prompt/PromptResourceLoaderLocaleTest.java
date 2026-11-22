package dev.workbench.framework.prompt;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** 验证中英双语 Prompt 加载、语言规范化与缺失英文资源时的中文回退。 */
class PromptResourceLoaderLocaleTest {

  @Test
  void normalizesSupportedLocales() {
    assertEquals("en-US", PromptResourceLoader.normalizeLocale("en"));
    assertEquals("en-US", PromptResourceLoader.normalizeLocale("en-GB"));
    assertEquals("en-US", PromptResourceLoader.normalizeLocale("EN_us"));
    assertEquals("zh-CN", PromptResourceLoader.normalizeLocale("zh-TW"));
    assertEquals("zh-CN", PromptResourceLoader.normalizeLocale("fr-FR"));
    assertEquals("zh-CN", PromptResourceLoader.normalizeLocale(null));
    assertTrue(PromptResourceLoader.isEnglish("en-US"));
    assertFalse(PromptResourceLoader.isEnglish("zh-CN"));
  }

  @Test
  void loadsEnglishPromptMirrorAndFallsBackToChinese() {
    String english = PromptResourceLoader.load("en-US", "agents/analyst.md");
    String chinese = PromptResourceLoader.load("zh-CN", "agents/analyst.md");
    assertNotEquals(chinese, english);
    assertTrue(english.contains("**Analysis Agent (analysis-agent)**"));
    assertFalse(english.contains("角色定位"));

    // 该资源只存在于中文目录，英文请求必须优雅回退而不是抛异常。
    assertEquals(
        PromptResourceLoader.load("zh-CN", "fallback-only.md"),
        PromptResourceLoader.load("en-US", "fallback-only.md"));
  }

  @Test
  void injectsOutputLanguageDirective() {
    assertTrue(PromptResourceLoader.outputLanguageDirective("en-US").contains("English"));
    assertTrue(PromptResourceLoader.outputLanguageDirective("zh-CN").contains("简体中文"));
  }

  @Test
  void loadsEnglishToolDescriptionsWithChineseFallback() {
    var english = PromptResourceLoader.tool("en-US", "workspace_search");
    var chinese = PromptResourceLoader.tool("zh-CN", "workspace_search");
    assertTrue(english.description().contains("case-sensitive"));
    assertTrue(chinese.description().contains("区分大小写"));
    assertFalse(english.parameter("query").isBlank());
  }
}
