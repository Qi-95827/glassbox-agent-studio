package dev.workbench.framework.context;

import java.util.List;
import java.util.Map;

/**
 * 单次模型调用的上下文预算快照。
 *
 * <p>{@code segmentTokens} 使用固定分段名（SYSTEM、SKILLS、MEMORY、HANDOFF、TOOL_OUTPUTS、HISTORY），
 * 前端据此绘制堆叠占比条；该快照不包含消息正文。
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
    String summaryPreview) {
  public ContextBreakdown {
    segmentTokens = segmentTokens == null ? Map.of() : Map.copyOf(segmentTokens);
    offloadedToolCallIds =
        offloadedToolCallIds == null ? List.of() : List.copyOf(offloadedToolCallIds);
    appliedTier = appliedTier == null ? CompactionTier.L0_OBSERVE_ONLY : appliedTier;
    summaryPreview = summaryPreview == null ? "" : summaryPreview;
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
        "");
  }

  public boolean compacted() {
    return appliedTier != CompactionTier.L0_OBSERVE_ONLY;
  }
}
