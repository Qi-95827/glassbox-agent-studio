package dev.workbench.task;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** 运行限额在领域边界也必须有效，不能依赖单个 HTTP 调用方。 */
class RunLimitsTest {
  @Test
  void invalidLimitsCannotBeConstructed() {
    assertThrows(
        IllegalArgumentException.class, () -> new RunLimits(0, 2, 2, null, null, 30, null, null));
    assertThrows(
        IllegalArgumentException.class, () -> new RunLimits(4, 2, 2, 0L, null, 30, null, null));
    assertThrows(
        IllegalArgumentException.class, () -> new RunLimits(4, 2, 2, null, null, 30, 0, null));
    assertThrows(
        IllegalArgumentException.class, () -> new RunLimits(4, 2, 2, null, null, 30, null, -1));
    assertEquals(0, new RunLimits(4, 2, 2, null, null, 30, null, 0).maxAgentTools());
  }
}
