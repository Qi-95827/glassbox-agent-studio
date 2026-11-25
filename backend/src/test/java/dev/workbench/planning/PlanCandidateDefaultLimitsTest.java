package dev.workbench.planning;

import static org.junit.jupiter.api.Assertions.*;

import dev.workbench.domain.RunLimits;
import org.junit.jupiter.api.Test;

/**
 * 候选计划物化运行的兜底预算。
 *
 * <p>反例对应 `F-07`：确认物化时把 {@link RunLimits} 全部字段设为 {@code null}，一旦模型陷入循环就没有 任何平台侧停机防线。
 */
class PlanCandidateDefaultLimitsTest {
  @Test
  void candidateRunsGetBoundedDefaultLimits() {
    RunLimits limits = PlanCandidateConfirmationService.defaultRunLimits();

    assertNotNull(limits.maxRounds(), "轮次上限不能为 null");
    assertNotNull(limits.maxTools(), "工具总数上限不能为 null");
    assertNotNull(limits.maxPerTool(), "单工具上限不能为 null");
    assertNotNull(limits.maxIdenticalTools(), "连续同指纹上限不能为 null");
    assertNotNull(limits.maxSeconds(), "最长执行时长不能为 null");
    assertNotNull(limits.maxAgentRounds(), "单 Agent 模型调用上限不能为 null");
    assertNotNull(limits.maxAgentTools(), "单 Agent 工具调用上限不能为 null");
    assertTrue(limits.maxRounds() <= 20);
    assertTrue(limits.maxSeconds() <= 300);
    assertTrue(limits.maxPerTool() <= 20);
  }

  @Test
  void defaultsAreStableAcrossCalls() {
    assertEquals(
        PlanCandidateConfirmationService.defaultRunLimits(),
        PlanCandidateConfirmationService.defaultRunLimits(),
        "兜底预算必须是确定的常量，不能随调用漂移");
  }
}
