package dev.workbench.orchestration;

import dev.workbench.framework.workflow.plan.PlanNode;
import dev.workbench.framework.workflow.plan.ReviewVerdict;
import dev.workbench.framework.workflow.plan.ReviewVerdictParser;
import dev.workbench.planning.PlanService;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 有界反思环的决策与上下文注入。
 *
 * <p>该类只读取持久化回边并生成批评反馈；真正的状态重置与尝试派生由
 * {@code WorkflowExecutionService.settleWorkflowNode} 在同一结算事务中完成，避免出现“已重置但未结算”的中间态。
 */
@Component
public class ReflectionCoordinator {
  private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
      new com.fasterxml.jackson.databind.ObjectMapper();

  private final PlanService plans;

  public ReflectionCoordinator(PlanService plans) {
    this.plans = plans;
  }

  /** 审查节点本轮是否要求修订，以及是否仍有回边额度。 */
  public Decision decide(String runId, PlanNode reviewer, String result) {
    if (reviewer == null || reviewer.role() == null) return Decision.notReview();
    var edge = plans.revisionEdgeForReviewer(runId, reviewer.id());
    if (edge == null) return Decision.notReview();
    var structured = ReviewVerdictParser.parseStructured(result);
    if (structured.isEmpty()) {
      // 配了反思回边却完全没有结构化裁决标记：旧实现默认 APPROVED，等于把「没说通过」当成「通过」。
      // 这里按保守策略进入修订；额度用尽则转 EXHAUSTED 由人工审核收口，绝不静默放行。
      var conservative =
          new ReviewVerdict(
              ReviewVerdict.REVISE_REQUIRED,
              java.util.List.of(),
              "审查输出缺少结构化裁决标记（DECISION/JSON/显式 CONCLUSION），按保守策略不判定为通过。");
      if (edge.revisionCount() >= edge.maxRevisions())
        return Decision.exhausted(conservative, edge.maxRevisions());
      return Decision.revise(
          conservative, edge.targetNodeId(), edge.revisionCount() + 1, edge.maxRevisions());
    }
    ReviewVerdict verdict = structured.get();
    if (!verdict.reviseRequired()) return Decision.approved(verdict);
    if (edge.revisionCount() >= edge.maxRevisions())
      return Decision.exhausted(verdict, edge.maxRevisions());
    return Decision.revise(verdict, edge.targetNodeId(), edge.revisionCount() + 1, edge.maxRevisions());
  }

  /**
   * 目标节点重试时注入上一轮批评反馈。
   *
   * <p>只从持久化回边读取；历史运行没有回边时返回空字符串，不伪造反馈。
   */
  public String criticFeedback(String runId, UUID targetNodeId) {
    return plans.revisionEdges(runId).stream()
        .filter(edge -> edge.targetNodeId().equals(targetNodeId))
        .map(PlanService.RevisionEdgeState::lastVerdictJson)
        .filter(value -> value != null && !value.isBlank())
        .map(ReflectionCoordinator::feedbackFromStored)
        .filter(value -> !value.isBlank())
        .findFirst()
        .orElse("");
  }

  private static String feedbackFromStored(String stored) {
    try {
      var node = JSON.readTree(stored);
      if (node == null || node.isNull()) return "";
      var defects = new StringBuilder();
      for (var defect : node.path("defects")) {
        defects
            .append("- [")
            .append(defect.path("severity").asText("MINOR"))
            .append("] ");
        String path = defect.path("filePath").asText("");
        if (!path.isBlank()) defects.append(path).append("：");
        defects.append(defect.path("summary").asText(""));
        String fix = defect.path("suggestedFix").asText("");
        if (!fix.isBlank()) defects.append("；建议：").append(fix);
        defects.append('\n');
      }
      return new ReviewVerdict(
              node.path("decision").asText(ReviewVerdict.REVISE_REQUIRED),
              java.util.List.of(),
              node.path("summary").asText(""))
          .feedbackText()
          + (defects.isEmpty() ? "" : "\n" + defects);
    } catch (Exception e) {
      return "";
    }
  }

  /** 决策结果；不能修订时携带明确的人工审核原因。 */
  public record Decision(
      Kind kind, ReviewVerdict verdict, UUID targetNodeId, int revision, int maxRevisions) {
    public static Decision notReview() {
      return new Decision(Kind.NOT_CONFIGURED, null, null, 0, 0);
    }

    public static Decision approved(ReviewVerdict verdict) {
      return new Decision(Kind.APPROVED, verdict, null, 0, 0);
    }

    public static Decision revise(
        ReviewVerdict verdict, UUID targetNodeId, int revision, int maxRevisions) {
      return new Decision(Kind.REVISE, verdict, targetNodeId, revision, maxRevisions);
    }

    public static Decision exhausted(ReviewVerdict verdict, int maxRevisions) {
      return new Decision(Kind.EXHAUSTED, verdict, null, maxRevisions, maxRevisions);
    }

    public boolean revisionRequired() {
      return kind == Kind.REVISE;
    }

    public boolean exhausted() {
      return kind == Kind.EXHAUSTED;
    }
  }

  public enum Kind {
    NOT_CONFIGURED,
    APPROVED,
    REVISE,
    EXHAUSTED
  }
}
