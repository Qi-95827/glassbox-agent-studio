package dev.workbench.control;

import dev.workbench.framework.context.CompactionRequest;
import dev.workbench.framework.context.CompactionResult;
import dev.workbench.framework.context.CompactionSummarizer;
import dev.workbench.framework.context.CompactionTier;
import dev.workbench.framework.context.ContextArtifactStore;
import dev.workbench.framework.context.ContextBreakdown;
import dev.workbench.framework.context.ContextCompactionPolicy;
import dev.workbench.framework.context.ContextMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 工业级四级上下文压缩（Query-Aware + Artifact 卸载 + 增量锚点摘要）。
 *
 * <p><b>L0</b> 只做精确分桶；<b>L1</b> 把超长工具输出完整落盘为 Artifact，并依据当前目标问题 选出最相关段落留在消息体，彻底废除“死留头 200 + 尾 100
 * 字”；<b>L2</b> 保留固定 System 锚点与最近 成对轮次，把中间历史压缩为可增量合并的四段结构化检查点；<b>L3</b> 仅在仍超限时按相关性从低到高 淘汰正文，绝不删除
 * System 规则、结构化锚点，也绝不拆散 {@code tool_call}/{@code tool_result} 成对关系。
 *
 * <p>本类只保留分级编排与对外契约：分段计量、摘录选择、L1 卸载、L2 摘要与 L3 硬保护分别由同包协作类承担。
 */
@Component
public class MultiTierContextCompactor implements ContextCompactionPolicy {
  private final ContextOffloadWriter offloadWriter;
  private final ContextCheckpointBuilder checkpointBuilder;
  private final ContextHardGuard hardGuard;

  public MultiTierContextCompactor() {
    this(List.of(), List.of());
  }

  @Autowired
  public MultiTierContextCompactor(
      List<ContextArtifactStore> artifactStores, List<CompactionSummarizer> summarizers) {
    this.offloadWriter = new ContextOffloadWriter(artifactStores);
    this.checkpointBuilder = new ContextCheckpointBuilder(summarizers);
    this.hardGuard = new ContextHardGuard();
  }

  @Override
  public CompactionResult evaluateAndCompact(CompactionRequest request) {
    List<ContextMessage> working = new ArrayList<>(request.messages());
    int before = estimate(working);
    int limit = request.contextLimit();
    String focus = focus(request, working);
    var tier = CompactionTier.L0_OBSERVE_ONLY;
    var offloaded = new ArrayList<String>();
    var artifactRefs = new ArrayList<String>();
    String summaryPreview = "";
    String structuredAnchors = "";
    String retainedPreview = "";
    var offloadDetails = new ArrayList<ContextBreakdown.OffloadDetail>();

    if (ratio(before, limit) >= request.l1Threshold()) {
      var result =
          offloadWriter.write(working, request.offloadAfterTurns(), focus, request.scope());
      if (result.changed()) {
        working = result.messages();
        offloaded.addAll(result.ids());
        artifactRefs.addAll(result.refs());
        offloadDetails.addAll(result.details());
        retainedPreview = result.preview();
        tier = CompactionTier.L1_TOOL_OFFLOAD;
      }
    }
    int afterL1 = estimate(working);
    int mergedFrom = -1;
    int mergedTo = -1;
    int mergedCount = 0;
    if (ratio(afterL1, limit) >= request.l2Threshold()) {
      // 合并范围要在摘要发生前留底：摘要后原消息已被替换，无法再回推是哪一段被压掉了。
      var beforeL2 = List.copyOf(working);
      var result = checkpointBuilder.build(working, request, focus);
      if (result.changed()) {
        working = result.messages();
        summaryPreview = result.preview();
        structuredAnchors = result.anchor();
        tier = CompactionTier.L2_SLIDING_SUMMARY;
        var surviving = new java.util.HashSet<Integer>();
        for (var message : working)
          if (message.originalIndex() != null) surviving.add(message.originalIndex());
        for (var message : beforeL2) {
          if (message.originalIndex() == null || surviving.contains(message.originalIndex()))
            continue;
          mergedCount++;
          int index = message.originalIndex();
          if (mergedFrom < 0 || index < mergedFrom) mergedFrom = index;
          if (index > mergedTo) mergedTo = index;
        }
      }
    }
    int afterL2 = estimate(working);
    if (ratio(afterL2, limit) >= request.l3Threshold()) {
      var result = hardGuard.guard(working, request, focus);
      if (result.changed()) {
        working = result.messages();
        if (summaryPreview.isBlank()) summaryPreview = result.preview();
        tier = CompactionTier.L3_HARD_GUARD;
      }
    }

    // L3 之后仍然保留的工具调用标识，就是被硬保底保护下来的「原子对」名单。
    // 只有真正触发 L3 才有这份明细：L0/L1/L2 不应把「当前仍在上下文里的工具调用」冒充成 L3 保留结果。
    var protectedToolCalls = new ArrayList<String>();
    if (tier == CompactionTier.L3_HARD_GUARD) {
      for (var message : working)
        if (message.toolCallId() != null && !protectedToolCalls.contains(message.toolCallId()))
          protectedToolCalls.add(message.toolCallId());
    }

    int total = estimate(working);
    // 预览与锚点会被持久化到 model_call_payloads 并展示在玻璃盒里：落库前统一脱敏，
    // 避免工具输出摘录或结构化锚点把模型 Key / Bearer 令牌原样带进快照。
    summaryPreview = dev.workbench.framework.trace.SensitiveDataMasker.mask(summaryPreview);
    retainedPreview = dev.workbench.framework.trace.SensitiveDataMasker.mask(retainedPreview);
    structuredAnchors = dev.workbench.framework.trace.SensitiveDataMasker.mask(structuredAnchors);
    var breakdown =
        new ContextBreakdown(
            limit,
            before,
            total,
            ContextSegmentEstimator.segments(working),
            tier,
            Math.max(0, before - total),
            before < 1 ? 0 : (int) Math.round((before - total) * 100.0 / before),
            List.copyOf(offloaded),
            summaryPreview,
            List.copyOf(artifactRefs),
            retainedPreview,
            structuredAnchors,
            new ContextBreakdown.CompactionDetail(
                List.copyOf(offloadDetails),
                mergedFrom,
                mergedTo,
                mergedCount,
                List.copyOf(protectedToolCalls)));
    return new CompactionResult(working, breakdown);
  }

  private static double ratio(int tokens, int limit) {
    return limit < 1 ? 0 : (double) tokens / limit;
  }

  /** 保守 Token 估算；保留在原类上供测试与外部调用点使用。 */
  static int estimate(List<ContextMessage> messages) {
    return ContextSegmentEstimator.estimate(messages);
  }

  /** 单段文本的保守 Token 估算；保留在原类上供测试与外部调用点使用。 */
  static int estimateText(String content) {
    return ContextSegmentEstimator.estimateText(content);
  }

  /** 单条消息的分段计数；保留在原类上供同包调用点使用。 */
  static Map<String, Integer> segmentTokens(ContextMessage message) {
    return ContextSegmentEstimator.segmentTokens(message);
  }

  /** 工具调用原子组边界对齐；保留在原类上供同包调用点使用。 */
  static int alignCut(List<ContextMessage> messages, int cut) {
    return ContextWindowBoundaries.alignCut(messages, cut);
  }

  private static String focus(CompactionRequest request, List<ContextMessage> messages) {
    if (request.focusQuery() != null && !request.focusQuery().isBlank())
      return request.focusQuery().strip();
    for (int index = messages.size() - 1; index >= 0; index--) {
      var message = messages.get(index);
      if ("USER".equals(message.role())
          && message.content() != null
          && !message.content().isBlank()) return message.content().strip();
    }
    return "";
  }
}
