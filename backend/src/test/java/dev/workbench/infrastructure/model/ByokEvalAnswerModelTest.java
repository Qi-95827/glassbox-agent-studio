package dev.workbench.infrastructure.model;

import static org.junit.jupiter.api.Assertions.*;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.model.ChatResponse;
import java.util.List;
import org.junit.jupiter.api.Test;

/** DeepSeek 思考预算修复：正文提取、思考块识别与规划阶段预算提升。 */
class ByokEvalAnswerModelTest {
  @Test
  void extractsOnlyTextBlocksAsFinalAnswer() {
    var response =
        ChatResponse.builder()
            .content(
                List.of(
                    ThinkingBlock.builder().thinking("内部推理链，不应作为答案。").build(),
                    TextBlock.builder().text("  A  ").build()))
            .build();
    assertEquals("A", ByokEvalAnswerModel.extractAnswerText(response));
    assertFalse(ByokEvalAnswerModel.hasThinkingOnly(response));
  }

  @Test
  void detectsThinkingOnlyResponseAsTruncated() {
    var response =
        ChatResponse.builder()
            .content(List.of(ThinkingBlock.builder().thinking("thinking...").build()))
            .build();
    assertTrue(ByokEvalAnswerModel.hasThinkingOnly(response));
    assertEquals("", ByokEvalAnswerModel.extractAnswerText(response));
  }

  @Test
  void raisesPlanningBudgetButKeepsAnswerBudgetForOfficialDeepSeek() {
    assertEquals(2_048, ByokEvalAnswerModel.tokenBudget(true, false, 2_048));
    assertEquals(4_096, ByokEvalAnswerModel.tokenBudget(true, true, 2_048));
    assertEquals(2_048, ByokEvalAnswerModel.tokenBudget(false, true, 2_048));
  }
}
