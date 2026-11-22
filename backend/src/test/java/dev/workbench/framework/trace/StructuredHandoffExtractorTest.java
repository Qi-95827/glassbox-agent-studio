package dev.workbench.framework.trace;

import static org.junit.jupiter.api.Assertions.*;

import dev.workbench.framework.workflow.plan.WorkflowHandoff;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 验证英文交接提取，同时保留中文关键词与旧自由文本兼容性。 */
class StructuredHandoffExtractorTest {

  private static WorkflowHandoff handoff(String text) {
    return new WorkflowHandoff(
        1,
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        Instant.parse("2026-01-01T00:00:00Z"),
        UUID.randomUUID(),
        text,
        List.of());
  }

  @Test
  void extractsEnglishSummaryDecisionsRisksAndArtifacts() {
    String text =
        """
        ## Observed Evidence
        | Item | Result |
        | --- | --- |
        Summary: The login refresh flow is ready for review.
        Decision: adopt a shared recoverSession helper in src/api/client.js.
        Risk: the retry limit is still unverified in tests.
        TODO: add coverage for src/auth/session.ts.
        """;
    var extracted = StructuredHandoffExtractor.extract(handoff(text), "en-US");

    assertEquals("Summary: The login refresh flow is ready for review.", extracted.summary());
    assertTrue(extracted.decisions().stream().anyMatch(item -> item.contains("Decision")));
    assertTrue(extracted.openRisks().stream().anyMatch(item -> item.contains("Risk")));
    assertTrue(extracted.openRisks().stream().anyMatch(item -> item.contains("TODO")));
    assertTrue(extracted.artifacts().contains("src/api/client.js"));
    assertTrue(extracted.artifacts().contains("src/auth/session.ts"));
    assertEquals("Direct upstream dependency match", extracted.validationChecks().getFirst().label());
  }

  @Test
  void keepsChineseCompatibilityAndSkipsPureHeadings() {
    String text =
        """
        ## 一、已观察到的证据
        结论摘要：已完成登录会话刷新逻辑分析。
        采用方案：新增共享的 recoverSession()。
        风险：并发写操作的 CSRF 刷新顺序未验证。
        """;
    var extracted = StructuredHandoffExtractor.extract(handoff(text));

    assertEquals("结论摘要：已完成登录会话刷新逻辑分析。", extracted.summary());
    assertTrue(extracted.decisions().stream().anyMatch(item -> item.contains("采用方案")));
    assertTrue(extracted.openRisks().stream().anyMatch(item -> item.contains("风险")));
    assertEquals("直接上游依赖匹配", extracted.validationChecks().getFirst().label());
  }
}
