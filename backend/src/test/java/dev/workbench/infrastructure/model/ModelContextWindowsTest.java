package dev.workbench.infrastructure.model;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** 上下文窗口推断：未知模型与自定义端点也必须有非零窗口，否则四级压缩永久失效。 */
class ModelContextWindowsTest {
  @AfterEach
  void clearOverrides() {
    System.clearProperty("workbench.model.context-window");
    System.clearProperty("workbench.model.default-context-window");
  }

  @Test
  void knownModelFamiliesGetNonZeroWindows() {
    assertTrue(ModelContextWindows.forModel("deepseek-flash") > 0);
    assertEquals(128_000, ModelContextWindows.forModel("deepseek-chat"));
    assertEquals(128_000, ModelContextWindows.forModel("qwen-max"));
    assertEquals(128_000, ModelContextWindows.forModel("glm-4"));
    assertEquals(200_000, ModelContextWindows.forModel("claude-3-7-sonnet"));
    assertEquals(128_000, ModelContextWindows.forModel("gpt-4o-mini"));
  }

  @Test
  void unknownOrCustomModelsFallBackToConfiguredDefault() {
    assertEquals(128_000, ModelContextWindows.forModel("my-self-hosted-model"));
    assertEquals(128_000, ModelContextWindows.forModel(null));

    System.setProperty("workbench.model.default-context-window", "32000");
    assertEquals(32_000, ModelContextWindows.forModel("my-self-hosted-model"));
  }

  @Test
  void explicitOverrideWinsForEveryModel() {
    System.setProperty("workbench.model.context-window", "64000");

    assertEquals(64_000, ModelContextWindows.forModel("deepseek-chat"));
    assertEquals(64_000, ModelContextWindows.forModel("unknown"));
  }

  @Test
  void invalidConfigurationIsIgnored() {
    System.setProperty("workbench.model.context-window", "0");
    System.setProperty("workbench.model.default-context-window", "-1");

    assertEquals(128_000, ModelContextWindows.forModel("deepseek-chat"));
    assertEquals(128_000, ModelContextWindows.forModel("unknown"));
  }
}
