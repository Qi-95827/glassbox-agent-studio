package dev.workbench.framework.workflow.check;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** 受信检查目录必须拒绝重复和未注册引用，不能回退到默认成功。 */
class SystemCheckRegistryTest {
  private static SystemCheckExecutor executor(SystemCheckRef ref) {
    return new SystemCheckExecutor() {
      @Override
      public SystemCheckRef ref() {
        return ref;
      }

      @Override
      public SystemCheckResult run(SystemCheckRequest request) {
        return SystemCheckResult.passed("测试检查通过");
      }
    };
  }

  @Test
  void resolvesRegisteredCheckAndRejectsUnknownVersion() {
    var registry =
        new SystemCheckRegistry(List.of(executor(new SystemCheckRef("sample-check", 1))));
    assertEquals(
        new SystemCheckRef("sample-check", 1),
        registry.resolve(new SystemCheckRef("sample-check", 1)).ref());
    assertTrue(registry.supports(new SystemCheckRef("sample-check", 1)));
    assertTrue(!registry.supports(new SystemCheckRef("sample-check", 2)));
    assertThrows(
        IllegalArgumentException.class,
        () -> registry.resolve(new SystemCheckRef("sample-check", 2)));
    assertThrows(
        IllegalArgumentException.class,
        () -> registry.resolve(new SystemCheckRef("unknown-check", 1)));
  }

  @Test
  void rejectsDuplicateReferenceAtStartup() {
    assertThrows(
        IllegalStateException.class,
        () ->
            new SystemCheckRegistry(
                List.of(
                    executor(new SystemCheckRef("sample-check", 1)),
                    executor(new SystemCheckRef("sample-check", 1)))));
  }

  @Test
  void rejectsResultWithoutTerminalStateOrSummary() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new SystemCheckResult(SystemCheckStatus.RUNNING, "运行中不能结算", null, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new SystemCheckResult(SystemCheckStatus.PASSED, " ", null, null));
  }
}
