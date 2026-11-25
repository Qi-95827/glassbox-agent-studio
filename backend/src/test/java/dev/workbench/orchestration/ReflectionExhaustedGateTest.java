package dev.workbench.orchestration;

import static org.junit.jupiter.api.Assertions.*;

import dev.workbench.framework.workflow.plan.ReviewVerdict;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 反思达上限与候选计划兜底预算。
 *
 * <p>反例对应 `F-05`：旧逻辑只按 {@code revisionRequired()}（仅 REVISE 为真）决定是否落裁决，REVISE 上限 用尽后的 EXHAUSTED
 * 被丢弃，结算看不到上限，运行被错结为 COMPLETED；候选计划则整份预算为 null， 一旦模型循环就没有平台侧停机防线。
 */
class ReflectionExhaustedGateTest {
  private static ReviewVerdict verdict() {
    return new ReviewVerdict(ReviewVerdict.REVISE_REQUIRED, List.of(), "缺少证据");
  }

  @Test
  void exhaustedDecisionKeepsItsVerdictForSettlement() {
    var decision = ReflectionCoordinator.Decision.exhausted(verdict(), 2);

    assertTrue(decision.exhausted());
    assertFalse(decision.revisionRequired(), "EXHAUSTED 不再重试，但仍必须携带裁决");
    assertNotNull(decision.verdict());
    assertTrue(
        WorkflowScheduler.carriesCriticVerdict(decision),
        "达到修订上限必须把裁决交给结算，转 BLOCKED/HUMAN_REVIEW_REQUIRED");
  }

  @Test
  void reviseAndApprovedAlsoCarryVerdicts() {
    assertTrue(
        WorkflowScheduler.carriesCriticVerdict(
            ReflectionCoordinator.Decision.revise(verdict(), UUID.randomUUID(), 1, 2)));
    assertTrue(
        WorkflowScheduler.carriesCriticVerdict(
            ReflectionCoordinator.Decision.approved(
                new ReviewVerdict(ReviewVerdict.APPROVED, List.of(), "通过"))));
  }

  @Test
  void nonReviewNodesCarryNoVerdict() {
    assertFalse(WorkflowScheduler.carriesCriticVerdict(ReflectionCoordinator.Decision.notReview()));
    assertFalse(WorkflowScheduler.carriesCriticVerdict(null));
  }
}
