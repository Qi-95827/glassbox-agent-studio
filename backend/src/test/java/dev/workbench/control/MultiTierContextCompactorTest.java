package dev.workbench.control;

import static org.junit.jupiter.api.Assertions.*;

import dev.workbench.framework.context.CompactionRequest;
import dev.workbench.framework.context.CompactionTier;
import dev.workbench.framework.context.ContextMessage;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 四级上下文压缩：阈值、工具卸载、成对保留与硬保护。 */
class MultiTierContextCompactorTest {
  private final MultiTierContextCompactor compactor = new MultiTierContextCompactor();

  @Test
  void l0OnlyObservesWhenBelowSixtyPercent() {
    var messages = conversation(6, 200);
    var result = compactor.evaluateAndCompact(CompactionRequest.of(messages, 50_000));
    assertEquals(CompactionTier.L0_OBSERVE_ONLY, result.breakdown().appliedTier());
    assertEquals(messages.size(), result.messages().size());
    assertTrue(result.breakdown().segmentTokens().containsKey("SYSTEM"));
  }

  @Test
  void l1OffloadsOnlyOlderLargeToolResultsAndKeepsPointer() {
    var messages = conversation(8, 2_000);
    int total = MultiTierContextCompactor.estimate(messages);
    var result =
        compactor.evaluateAndCompact(
            new CompactionRequest(messages, (int) (total / 0.65), 4, 2, 0.60, 0.78, 0.92));
    assertEquals(CompactionTier.L1_TOOL_OFFLOAD, result.breakdown().appliedTier());
    assertFalse(result.breakdown().offloadedToolCallIds().isEmpty());
    assertTrue(
        result.messages().stream()
            .anyMatch(message -> message.content().contains("[Offloaded: toolCallId=")));
    // 最近两轮工具结果必须保持完整。
    long intact =
        result.messages().stream()
            .filter(message -> "TOOL".equals(message.role()) && message.content().length() > 1_500)
            .count();
    assertTrue(intact >= 2, "最近两轮工具结果不能被卸载");
  }

  @Test
  void l2KeepsPinnedAnchorAndRecentRoundsAndBuildsCheckpointSummary() {
    var messages = conversation(12, 200);
    int total = MultiTierContextCompactor.estimate(messages);
    var result =
        compactor.evaluateAndCompact(
            new CompactionRequest(messages, (int) (total / 0.85), 4, 2, 0.60, 0.78, 0.95));
    assertTrue(
        result.breakdown().appliedTier() == CompactionTier.L2_SLIDING_SUMMARY
            || result.breakdown().appliedTier() == CompactionTier.L3_HARD_GUARD);
    assertTrue(result.messages().getFirst().content().contains("系统规则"));
    // 检查点插在首条 USER 之前；任务目标仍是第一条 USER 消息。
    assertTrue(
        result.messages().stream()
            .filter(message -> "USER".equals(message.role()))
            .findFirst()
            .orElseThrow()
            .content()
            .contains("任务目标"));
    assertTrue(
        result.messages().stream().anyMatch(message -> message.content().contains("上下文压缩检查点")));
    // 最近四轮完整保留：至少 4 条 Assistant 消息仍在尾部。
    long assistants =
        result.messages().stream().filter(message -> "ASSISTANT".equals(message.role())).count();
    assertTrue(assistants >= 4);
  }

  @Test
  void l3HardGuardNeverLeavesTheOldestToolPairsUnbounded() {
    var messages = conversation(20, 3_000);
    var result =
        compactor.evaluateAndCompact(new CompactionRequest(messages, 500, 4, 2, 0.60, 0.78, 0.92));
    assertEquals(CompactionTier.L3_HARD_GUARD, result.breakdown().appliedTier());
    assertTrue(result.messages().size() < messages.size());
    assertTrue(
        result.messages().stream().anyMatch(message -> message.content().contains("上下文硬保护")));
  }

  private static List<ContextMessage> conversation(int rounds, int toolChars) {
    var messages = new ArrayList<ContextMessage>();
    messages.add(new ContextMessage("SYSTEM", "系统规则：不得扩大授权范围。"));
    messages.add(new ContextMessage("USER", "任务目标：完成受控修改并给出验收证据。"));
    for (int index = 0; index < rounds; index++) {
      messages.add(new ContextMessage("ASSISTANT", "assistant round " + index + " decision"));
      messages.add(
          new ContextMessage(
              "TOOL",
              "read_authorized_file",
              "line " + index + " ".repeat(toolChars),
              "tool-" + index));
    }
    return messages;
  }
}
