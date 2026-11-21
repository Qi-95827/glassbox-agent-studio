package dev.workbench.framework.trace;

import static org.junit.jupiter.api.Assertions.*;

import dev.workbench.domain.ToolKind;
import dev.workbench.framework.workflow.plan.WorkflowHandoff;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 验证玻璃盒脱敏截断与结构化交接信封提取。 */
class GlassboxTraceTest {

  @Test
  void masksApiKeysAndBearerTokens() {
    String raw =
        "Authorization: Bearer abcdef1234567890\napi_key=sk-1234567890abcdef123456\npassword: mySecretValue99";
    String masked = SensitiveDataMasker.mask(raw);
    assertFalse(masked.contains("abcdef1234567890"));
    assertFalse(masked.contains("sk-1234567890abcdef123456"));
    assertFalse(masked.contains("mySecretValue99"));
    assertTrue(masked.contains("***MASKED***"));
  }

  @Test
  void truncatesOversizedPayloadAndFlagsTruncated() {
    String huge = "x".repeat(500);
    var res = SensitiveDataMasker.maskAndTruncate(huge, 300);
    assertTrue(res.truncated());
    assertTrue(res.text().contains("已截断"));
  }

  @Test
  void extractsStructuredHandoffEnvelope() {
    var handoff =
        new WorkflowHandoff(
            1,
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            Instant.now(),
            UUID.randomUUID(),
            "完成排序算法模块实现\n方案：采用模块化 sort.js 并暴露单步迭代器。\n风险：大数据量下动画帧率待确认。",
            List.of(
                new WorkflowHandoff.ToolEvidence(
                    UUID.randomUUID(), ToolKind.WORKSPACE_WRITE, 1, "{\"sort.js\":\"sha256\"}")));
    var structured = StructuredHandoffExtractor.extract(handoff);
    assertEquals("完成排序算法模块实现", structured.summary());
    assertFalse(structured.decisions().isEmpty());
    assertTrue(structured.artifacts().contains("sort.js"));
    assertFalse(structured.openRisks().isEmpty());
    assertEquals(4, structured.validationChecks().size());
    assertTrue(structured.validationChecks().stream().allMatch(c -> "PASS".equals(c.status())));
  }
}
