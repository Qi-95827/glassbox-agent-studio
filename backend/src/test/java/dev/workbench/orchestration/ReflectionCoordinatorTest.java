package dev.workbench.orchestration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.workbench.domain.AgentRole;
import dev.workbench.framework.workflow.plan.PlanNode;
import dev.workbench.framework.workflow.plan.PlanNodeKind;
import dev.workbench.framework.workflow.plan.PlanNodeStatus;
import dev.workbench.planning.PlanService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 反思协调器的裁决边界。
 *
 * <p>审查节点配置了反思回边时：显式 APPROVED 才通过；显式 REVISE_REQUIRED 进入修订；完全没有结构化裁决
 * 标记时按保守策略进入修订而不是静默通过。末尾标记即使位于 2500 字之后也必须被识别。
 */
class ReflectionCoordinatorTest {
  private final PlanService plans = mock(PlanService.class);
  private ReflectionCoordinator coordinator;
  private PlanNode reviewer;
  private UUID targetId;

  @BeforeEach
  void setUp() {
    coordinator = new ReflectionCoordinator(plans);
    reviewer = node("review", AgentRole.REVIEWER);
    targetId = UUID.randomUUID();
    when(plans.revisionEdgeForReviewer("run-1", reviewer.id()))
        .thenReturn(new PlanService.RevisionEdgeState(reviewer.id(), targetId, 2, 0, null));
  }

  @Test
  void proseOnlyReviewOutputIsNotSilentlyApproved() {
    var decision = coordinator.decide("run-1", reviewer, "整体看起来可以，细节我再看看。");

    assertTrue(decision.revisionRequired(), "缺少结构化裁决时不得判定通过");
    assertEquals(targetId, decision.targetNodeId());
    assertTrue(decision.verdict().reviseRequired());
    assertTrue(decision.verdict().summary().contains("保守"));
  }

  @Test
  void explicitApprovalStillPasses() {
    var decision = coordinator.decide("run-1", reviewer, "DECISION: APPROVED\n检查通过。");

    assertEquals(ReflectionCoordinator.Kind.APPROVED, decision.kind());
  }

  @Test
  void trailingRevisionMarkerBeyondTheOldTruncationPointStillTriggersRevision() {
    String full = "逐条核对：" + "证据不足需要补充。".repeat(320) + "\nDECISION: REVISE_REQUIRED\n请补齐测试。";
    assertTrue(full.length() > 2500);

    var decision = coordinator.decide("run-1", reviewer, full);

    assertTrue(decision.revisionRequired());
    assertEquals(targetId, decision.targetNodeId());
  }

  @Test
  void exhaustedBudgetTurnsConservativelyIntoHumanReviewInsteadOfApproving() {
    when(plans.revisionEdgeForReviewer("run-1", reviewer.id()))
        .thenReturn(new PlanService.RevisionEdgeState(reviewer.id(), targetId, 2, 2, null));

    var decision = coordinator.decide("run-1", reviewer, "没写结论");

    assertTrue(decision.exhausted());
    assertTrue(decision.verdict().reviseRequired());
  }

  private static PlanNode node(String key, AgentRole role) {
    return new PlanNode(
        UUID.randomUUID(),
        UUID.randomUUID(),
        0,
        role,
        null,
        PlanNodeStatus.RUNNING,
        null,
        null,
        null,
        Instant.now(),
        List.of(),
        new dev.workbench.framework.agent.definition.AgentDefinitionRef("reviewer", 1),
        "a".repeat(64),
        UUID.randomUUID(),
        key,
        "审查",
        "review",
        true,
        false,
        null,
        PlanNodeKind.AGENT,
        null);
  }
}
