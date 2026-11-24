package dev.workbench.control;

import static org.junit.jupiter.api.Assertions.*;

import dev.workbench.framework.context.CompactionRequest;
import dev.workbench.framework.context.CompactionTier;
import dev.workbench.framework.context.ContextMessage;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

/** Phase 4：L1 Query-Aware 摘录、L2 增量锚点合并与 Artifact 引用。 */
class MultiTierContextCompactorQueryAwareTest {
  private final MultiTierContextCompactor compactor = new MultiTierContextCompactor();

  @Test
  void l1KeepsMiddleNeedleInsteadOfBruteForceHeadTail() {
    String needle = "root cause: E4212 signature mismatch at src/auth/session.js";
    var messages = new ArrayList<ContextMessage>();
    messages.add(new ContextMessage("SYSTEM", "系统规则：不得扩大授权范围。"));
    messages.add(new ContextMessage("USER", "排查 E4212 报错根因并给出修复证据。"));
    for (int index = 0; index < 8; index++) {
      messages.add(new ContextMessage("ASSISTANT", "round " + index + " inspect"));
      String content = blob(index, needle, index == 3);
      messages.add(new ContextMessage("TOOL", "read_authorized_file", content, "tool-" + index));
    }
    int total = MultiTierContextCompactor.estimate(messages);
    var result =
        compactor.evaluateAndCompact(
            new CompactionRequest(
                messages,
                (int) (total / 0.70),
                2,
                2,
                0.60,
                0.95,
                0.99,
                "E4212 signature mismatch root cause"));
    assertEquals(CompactionTier.L1_TOOL_OFFLOAD, result.breakdown().appliedTier());
    assertFalse(result.breakdown().artifactRefs().isEmpty());
    String compacted =
        result.messages().stream()
            .map(ContextMessage::content)
            .reduce("", (left, right) -> left + "\n" + right);
    assertTrue(compacted.contains("E4212"), "中间埋针必须被 Query-Aware 摘录保留");
    assertTrue(compacted.contains("[ArtifactRef:"));
    assertTrue(compacted.contains("src/auth/session.js"));
    assertFalse(result.breakdown().retainedExcerptPreview().isBlank());
  }

  @Test
  void l2IncrementallyMergesPreviousCheckpointWithoutLosingFacts() {
    var messages = new ArrayList<ContextMessage>();
    messages.add(new ContextMessage("SYSTEM", "系统规则：不得输出密钥。"));
    messages.add(new ContextMessage("USER", "整理部署约束。"));
    messages.add(new ContextMessage("ASSISTANT", "先确认区域。"));
    messages.add(new ContextMessage("TOOL", "read_config", "部署区域是 cn-north-1，只读副本位于同区域。", "t1"));
    messages.add(new ContextMessage("ASSISTANT", "继续检查重试。"));
    messages.add(new ContextMessage("TOOL", "read_config", "禁止跨区域写入。", "t2"));
    messages.add(new ContextMessage("USER", "最新进展？"));
    messages.add(new ContextMessage("ASSISTANT", "最近一轮完整保留。"));

    int total = MultiTierContextCompactor.estimate(messages);
    var first =
        compactor.evaluateAndCompact(
            new CompactionRequest(messages, (int) (total / 0.92), 1, 1, 0.60, 0.80, 0.95));
    assertTrue(
        first.messages().stream().anyMatch(message -> message.content().contains("cn-north-1")),
        "首轮检查点必须保留区域事实");

    var extended = new ArrayList<>(first.messages());
    extended.add(new ContextMessage("ASSISTANT", "继续排查。"));
    extended.add(new ContextMessage("TOOL", "read_config", "重试上限为 3 次，超过必须人工介入。", "t3"));
    extended.add(new ContextMessage("USER", "再确认一次。"));
    extended.add(new ContextMessage("ASSISTANT", "最近一轮完整保留。"));
    int extendedTotal = MultiTierContextCompactor.estimate(extended);
    var second =
        compactor.evaluateAndCompact(
            new CompactionRequest(extended, (int) (extendedTotal / 0.92), 1, 1, 0.60, 0.80, 0.95));
    String checkpoints =
        second.messages().stream()
            .filter(message -> message.content().contains("【上下文压缩检查点】"))
            .map(ContextMessage::content)
            .reduce("", (left, right) -> left + "\n" + right);
    assertTrue(checkpoints.contains("cn-north-1"), "旧事实不能因二次摘要退化");
    assertTrue(checkpoints.contains("3 次"), "新增事实必须增量合并");
    assertEquals(
        1,
        second.messages().stream()
            .filter(message -> message.content().contains("【上下文压缩检查点】"))
            .count(),
        "只允许一个合并后的检查点");
  }

  @Test
  void l3PreservesToolCallPairsAndAnchors() {
    var messages = new ArrayList<ContextMessage>();
    messages.add(new ContextMessage("SYSTEM", "系统规则：不得删除安全约束。"));
    messages.add(new ContextMessage("USER", "长任务"));
    for (int index = 0; index < 16; index++) {
      messages.add(new ContextMessage("ASSISTANT", "assistant " + index));
      messages.add(
          new ContextMessage(
              "TOOL",
              "read_authorized_file",
              "line " + index + " " + "x".repeat(2_000),
              "pair-" + index));
    }
    var result =
        compactor.evaluateAndCompact(
            new CompactionRequest(messages, 700, 2, 2, 0.60, 0.78, 0.92, "line 3"));
    assertEquals(CompactionTier.L3_HARD_GUARD, result.breakdown().appliedTier());
    var toolIds = new ArrayList<String>();
    for (var message : result.messages()) {
      if ("TOOL".equals(message.role())) {
        assertNotNull(message.toolCallId(), "工具结果不得丢失 callId");
        toolIds.add(message.toolCallId());
      }
    }
    assertEquals(toolIds.size(), toolIds.stream().distinct().count(), "工具调用与结果保持成对唯一");
    assertTrue(
        result.messages().stream().anyMatch(message -> message.content().contains("系统规则")),
        "System 规则不得被裁剪");
    assertTrue(
        result.messages().stream().anyMatch(message -> message.content().contains("【上下文硬保护】")));
  }

  private static String blob(int index, String needle, boolean inject) {
    var builder = new StringBuilder();
    builder.append("file=src/module").append(index).append(".js\n");
    int target = 1_200;
    while (builder.length() < 2_400) {
      if (inject && builder.length() >= target) builder.append(needle).append('\n');
      builder
          .append("line ")
          .append(builder.length())
          .append(": routine inspection without mutation\n");
    }
    return builder.substring(0, 2_400);
  }
}
