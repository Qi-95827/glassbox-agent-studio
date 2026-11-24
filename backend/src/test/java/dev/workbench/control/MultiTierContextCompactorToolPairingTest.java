package dev.workbench.control;

import static org.junit.jupiter.api.Assertions.*;

import dev.workbench.framework.context.CompactionRequest;
import dev.workbench.framework.context.CompactionTier;
import dev.workbench.framework.context.ContextMessage;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 工具调用原子组、SYSTEM 位置、L3 空转与中文估算。
 *
 * <p>反例对应 `F-01`：L2/L3 把 assistant(tool_use) 与其 TOOL 结果拆开会被 OpenAI/DeepSeek 直接 400； 中途插入 SYSTEM
 * 破坏严格角色序列；硬保护在单条消息上反复叠加前缀空转 200 次。
 */
class MultiTierContextCompactorToolPairingTest {
  private final MultiTierContextCompactor compactor = new MultiTierContextCompactor();

  private static String body(String tag, int chars) {
    var text = new StringBuilder(tag);
    while (text.length() < chars) text.append("工具输出片段").append(text.length()).append('\n');
    return text.toString();
  }

  /** 交替的「assistant 调用 + TOOL 结果」对话，每轮正文可控。 */
  private static List<ContextMessage> pairedConversation(int pairs, int chars) {
    var messages = new ArrayList<ContextMessage>();
    messages.add(new ContextMessage("SYSTEM", "你是受控执行器。"));
    messages.add(new ContextMessage("USER", "请完成重构并给出证据。"));
    for (int index = 0; index < pairs; index++) {
      messages.add(
          new ContextMessage("ASSISTANT", null, "turn-" + index + " 需要读取文件", "call-" + index));
      messages.add(new ContextMessage("TOOL", "read_file", body("结果", chars), "call-" + index));
    }
    return messages;
  }

  /** 断言：每个 TOOL 都有前置且 id 匹配的 assistant(tool_use)，且每个调用后都有结果。 */
  private static void assertPairsIntact(List<ContextMessage> messages) {
    var pending = new ArrayList<String>();
    for (var message : messages) {
      if (message.toolCall()) {
        pending.add(message.toolCallId());
        continue;
      }
      if (message.toolResult()) {
        assertFalse(pending.isEmpty(), "出现孤儿 TOOL 结果：" + message.toolCallId());
        assertTrue(pending.remove(message.toolCallId()), "TOOL 结果找不到对应调用：" + message.toolCallId());
      }
    }
    assertTrue(pending.isEmpty(), "存在没有结果的工具调用：" + pending);
  }

  private static void assertNoSystemAfterFirstUser(List<ContextMessage> messages) {
    boolean seenUser = false;
    var roles = new StringBuilder();
    for (var message : messages) {
      roles.append(message.role()).append(' ');
      if ("USER".equals(message.role())) seenUser = true;
      if (seenUser)
        assertNotEquals("SYSTEM", message.role(), "首条 USER 之后不得再出现 SYSTEM 消息；实际序列：" + roles);
    }
  }

  @Test
  void l2NeverSplitsToolCallFromItsResult() {
    var messages = pairedConversation(5, 1_200);
    int total = MultiTierContextCompactor.estimate(messages);
    var result =
        compactor.evaluateAndCompact(
            new CompactionRequest(messages, (int) (total / 0.85), 2, 2, 0.60, 0.78, 0.92));

    assertTrue(
        result.breakdown().appliedTier() == CompactionTier.L2_SLIDING_SUMMARY
            || result.breakdown().appliedTier() == CompactionTier.L1_TOOL_OFFLOAD,
        "该体量应触发 L1 或 L2，实际：" + result.breakdown().appliedTier());
    assertPairsIntact(result.messages());
    assertNoSystemAfterFirstUser(result.messages());
  }

  @Test
  void l3HardGuardKeepsPairsAndTerminates() {
    var messages = pairedConversation(8, 2_000);
    int total = MultiTierContextCompactor.estimate(messages);
    // 预算逐步收紧，直到 L1/L2 都不足以压回水位，必须由 L3 兜底。
    dev.workbench.framework.context.CompactionResult result = null;
    for (int divisor = 1; divisor <= 8; divisor++) {
      result =
          compactor.evaluateAndCompact(
              new CompactionRequest(
                  messages, Math.max(1, total / divisor), 4, 2, 0.60, 0.78, 0.92));
      if (result.breakdown().appliedTier() == CompactionTier.L3_HARD_GUARD) break;
    }

    assertEquals(CompactionTier.L3_HARD_GUARD, result.breakdown().appliedTier(), "必须最终进入 L3 硬保护");
    assertPairsIntact(result.messages());
    assertNoSystemAfterFirstUser(result.messages());
    // 每条消息最多被裁一次：不允许同一正文被反复叠加 [HardGuard excerpt] 前缀。
    for (var message : result.messages()) {
      String content = message.content() == null ? "" : message.content();
      int marker = content.split("\\[HardGuard excerpt:", -1).length - 1;
      assertTrue(marker <= 1, "同一消息被重复裁剪：" + marker);
    }
  }

  @Test
  void l3DoesNotSpinWhenNothingCanShrinkFurther() {
    // 只有一条很小的工具结果：无法再压缩，必须在有限步内结束且保持可发送。
    var messages =
        List.of(
            new ContextMessage("SYSTEM", "系统提示"),
            new ContextMessage("USER", "目标"),
            new ContextMessage("ASSISTANT", null, "调用", "call-x"),
            new ContextMessage("TOOL", "read_file", "短结果", "call-x"));
    int total = MultiTierContextCompactor.estimate(messages);
    var result =
        compactor.evaluateAndCompact(
            new CompactionRequest(messages, Math.max(1, total - 1), 1, 1, 0.60, 0.78, 0.92));

    assertPairsIntact(result.messages());
    long notices =
        result.messages().stream()
            .filter(message -> message.content() != null && message.content().contains("HardGuard"))
            .count();
    assertTrue(notices <= 2, "硬保护通知不应重复堆叠，实际：" + notices);
  }

  @Test
  void chineseTextEstimateIsNotUnderestimated() {
    String chinese = "上下文压缩必须保持工具调用与结果成对".repeat(20);
    int estimated =
        MultiTierContextCompactor.estimate(List.of(new ContextMessage("USER", chinese)));
    int chars = chinese.length();

    // 中文字符至少按 1 token 估，绝不低于字符数的 1.2 倍（旧实现只有 1/4）。
    assertTrue(estimated >= chars * 1.2, "中文估算偏低：" + estimated + " / " + chars);
    assertTrue(estimated <= chars * 2, "中文估算不应过度膨胀：" + estimated + " / " + chars);
  }

  @Test
  void checkedInheritedCheckpointParticipatesInIncrementalSummary() {
    var messages = pairedConversation(6, 1_500);
    int total = MultiTierContextCompactor.estimate(messages);
    var request =
        new CompactionRequest(messages, (int) (total / 0.80), 2, 2, 0.60, 0.78, 0.92)
            .withPreviousCheckpoint("【上一轮检查点】已完成 A 与 B，剩余 C。");
    var result = compactor.evaluateAndCompact(request);

    assertTrue(result.breakdown().appliedTier() != CompactionTier.L0_OBSERVE_ONLY, "带入旧检查点后仍应触发压缩");
    assertPairsIntact(result.messages());
  }
}
