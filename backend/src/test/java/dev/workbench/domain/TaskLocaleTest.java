package dev.workbench.domain;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 验证任务语言的向后兼容默认值与英文规范化。 */
class TaskLocaleTest {

  @Test
  void defaultsToChineseForLegacyConstructors() {
    TaskDefinition task =
        new TaskDefinition(
            UUID.randomUUID(),
            "目标",
            Scenario.NORMAL,
            ModelMode.MOCK,
            null,
            null,
            false,
            null,
            false,
            List.of(),
            List.of(),
            TaskEntryKind.GENERAL,
            List.of(),
            Map.of(),
            Map.of());
    assertEquals("zh-CN", task.locale());
  }

  @Test
  void normalizesEnglishLocale() {
    TaskDefinition task =
        new TaskDefinition(
            UUID.randomUUID(),
            "Build a page",
            Scenario.NORMAL,
            ModelMode.MOCK,
            null,
            null,
            false,
            null,
            false,
            List.of(),
            List.of("Page opens"),
            TaskEntryKind.GENERAL,
            List.of(),
            Map.of(),
            Map.of(),
            null,
            "en-GB");
    assertEquals("en-US", task.locale());
  }
}
