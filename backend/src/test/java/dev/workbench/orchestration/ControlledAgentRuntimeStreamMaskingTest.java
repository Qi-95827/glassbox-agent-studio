package dev.workbench.orchestration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 实时 SSE 帧（模型正文与思考链增量）在离开发送端之前必须与落库快照同样脱敏。
 *
 * <p>回归：落库快照已脱敏，但 {@code MODEL_DELTA}/{@code MODEL_REASONING_DELTA} 原文直出，模型把 Key
 * 写进回答时实时通道会先泄露一次。
 */
class ControlledAgentRuntimeStreamMaskingTest {
  @Test
  void streamedDeltasNeverCarryPlaintextSecrets() {
    String secret = "sk-live-9f8e7d6c5b4a3210";
    String bearer = "Bearer eyJhbGciOiJIUzI1NiJ9.payload.signature";
    String answer = "已核对：key=" + secret + "，请求头 " + bearer + "，其余正常。";

    String safe = ControlledAgentRuntime.maskTransientText(answer);

    assertFalse(safe.contains(secret), safe);
    assertFalse(safe.contains(bearer), safe);
    assertTrue(safe.contains("***MASKED"), safe);
    // 脱敏只替换敏感形态，不改变其余语义，前端流式观感不受影响。
    assertTrue(safe.contains("已核对"), safe);
  }
}
