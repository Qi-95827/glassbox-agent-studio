package dev.workbench.framework.context;

import java.util.List;
import java.util.Map;

/**
 * 单次模型调用的上下文预算快照。
 *
 * <p>{@code segmentTokens} 使用固定分段名（SYSTEM、SKILLS、MEMORY、HANDOFF、TOOL_OUTPUTS、HISTORY）；
 * {@code artifactRefs} 记录 L1 卸载引用，{@code retainedExcerptPreview} 展示 Query-Aware 摘录，
 * {@code structuredAnchors} 展示 L2 结构化检查点，全部供玻璃盒抽屉穿透。该快照不包含密钥。
 */
public record ContextBreakdown(
    int modelContextLimit,
    int beforeTokens,
    int totalEstimatedTokens,
    Map<String, Integer> segmentTokens,
    CompactionTier appliedTier,
    int savedTokens,
    int savedPercent,
    List<String> offloadedToolCallIds,
    String summaryPreview,
    List<String> artifactRefs,
    String retainedExcerptPreview,
    String structuredAnchors) {
  public ContextBreakdown {
    segmentTokens = segmentTokens == null ? Map.of() : Map.copyOf(segmentTokens);
    offloadedToolCallIds =
        offloadedToolCallIds == null ? List.of() : List.copyOf(offloadedToolCallIds);
    artifactRefs = artifactRefs == null ? List.of() : List.copyOf(artifactRefs);
    appliedTier = appliedTier == null ? CompactionTier.L0_OBSERVE_ONLY : appliedTier;
    summaryPreview = summaryPreview == null ? "" : summaryPreview;
    retainedExcerptPreview = retainedExcerptPreview == null ? "" : retainedExcerptPreview;
    structuredAnchors = structuredAnchors == null ? "" : structuredAnchors;
  }

  /** 兼容只记录总量、没有分段信息的历史快照。 */
  public ContextBreakdown(int modelContextLimit, int totalEstimatedTokens) {
    this(
        modelContextLimit,
        totalEstimatedTokens,
        totalEstimatedTokens,
        Map.of("HISTORY", totalEstimatedTokens),
        CompactionTier.L0_OBSERVE_ONLY,
        0,
        0,
        List.of(),
        "",
        List.of(),
        "",
        "");
  }

  /** 兼容旧调用点（评测 Runner 的消融分支）。 */
  public ContextBreakdown(
      int modelContextLimit,
      int beforeTokens,
      int totalEstimatedTokens,
      Map<String, Integer> segmentTokens,
      CompactionTier appliedTier,
      int savedTokens,
      int savedPercent,
      List<String> offloadedToolCallIds,
      String summaryPreview) {
    this(
        modelContextLimit,
        beforeTokens,
        totalEstimatedTokens,
        segmentTokens,
        appliedTier,
        savedTokens,
        savedPercent,
        offloadedToolCallIds,
        summaryPreview,
        List.of(),
        "",
        "");
  }

  public boolean compacted() {
    return appliedTier != CompactionTier.L0_OBSERVE_ONLY;
  }
}
