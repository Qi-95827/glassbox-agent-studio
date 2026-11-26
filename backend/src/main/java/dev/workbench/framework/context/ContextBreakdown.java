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
    String structuredAnchors,
    CompactionDetail detail) {
  public ContextBreakdown {
    segmentTokens = segmentTokens == null ? Map.of() : Map.copyOf(segmentTokens);
    offloadedToolCallIds =
        offloadedToolCallIds == null ? List.of() : List.copyOf(offloadedToolCallIds);
    artifactRefs = artifactRefs == null ? List.of() : List.copyOf(artifactRefs);
    appliedTier = appliedTier == null ? CompactionTier.L0_OBSERVE_ONLY : appliedTier;
    summaryPreview = summaryPreview == null ? "" : summaryPreview;
    retainedExcerptPreview = retainedExcerptPreview == null ? "" : retainedExcerptPreview;
    structuredAnchors = structuredAnchors == null ? "" : structuredAnchors;
    detail = detail == null ? CompactionDetail.EMPTY : detail;
  }

  /**
   * 结构化压缩明细：让前端可以逐条解释「到底删/改了什么」，而不是只看到一个节省百分比。
   *
   * @param offloads L1 逐条卸载记录（被卸载的工具调用、产物引用、原文与保留摘录字符数）。
   * @param mergedFrom 被 L2 摘要合并的首条消息原始下标；未发生 L2 时为 -1。
   * @param mergedTo 被 L2 摘要合并的末条消息原始下标；未发生 L2 时为 -1。
   * @param mergedMessageCount 被 L2 摘要替换掉的消息条数。
   * @param protectedToolCallIds L3 之后仍成对保留的工具调用标识。
   */
  public record CompactionDetail(
      List<OffloadDetail> offloads,
      int mergedFrom,
      int mergedTo,
      int mergedMessageCount,
      List<String> protectedToolCallIds) {
    public static final CompactionDetail EMPTY =
        new CompactionDetail(List.of(), -1, -1, 0, List.of());

    public CompactionDetail {
      offloads = offloads == null ? List.of() : List.copyOf(offloads);
      protectedToolCallIds =
          protectedToolCallIds == null ? List.of() : List.copyOf(protectedToolCallIds);
    }
  }

  /**
   * 单条 L1 卸载记录。
   *
   * @param toolCallId 被卸载的工具调用标识。
   * @param artifactRef 落库产物引用，可用于 {@code /api/context-artifacts/{ref}} 回读原文。
   * @param originalChars 原始工具输出字符数。
   * @param retainedChars 原位保留的摘录字符数。
   */
  public record OffloadDetail(
      String toolCallId, String artifactRef, int originalChars, int retainedChars) {}

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
        "",
        CompactionDetail.EMPTY);
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
        "",
        CompactionDetail.EMPTY);
  }

  /** 兼容尚未记录结构化压缩明细的调用点（测试与历史快照重建）。 */
  public ContextBreakdown(
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
        artifactRefs,
        retainedExcerptPreview,
        structuredAnchors,
        CompactionDetail.EMPTY);
  }

  public boolean compacted() {
    return appliedTier != CompactionTier.L0_OBSERVE_ONLY;
  }
}
