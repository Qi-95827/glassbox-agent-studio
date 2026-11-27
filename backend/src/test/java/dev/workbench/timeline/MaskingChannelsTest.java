package dev.workbench.timeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.workbench.framework.context.CompactionRequest;
import dev.workbench.framework.context.ContextMessage;
import dev.workbench.domain.ExecutionScope;
import dev.workbench.domain.ExecutionScopeResolver;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 全通道脱敏口径：外部 Trace 元数据与压缩快照里的密钥/令牌必须与落库正文一致地脱敏。
 *
 * <p>回归：落库快照已脱敏，但压缩摘录预览/结构化锚点与外部 Trace 的 run.name/source、span.name 是旁路，
 * 会把模型吐出的 `sk-...` 或 `Bearer ...` 原样带进持久化与只读透视。
 */
class MaskingChannelsTest {
  private static final String SECRET = "sk-live-9f8e7d6c5b4a3210";
  private static final String BEARER = "Bearer eyJhbGciOiJIUzI1NiJ9.payload.signature";

  @Test
  void compactionPreviewAndAnchorsAreMaskedBeforeTheBreakdownIsReturned() {
    var compactor = new dev.workbench.control.MultiTierContextCompactor();
    var messages = new ArrayList<ContextMessage>();
    messages.add(new ContextMessage("SYSTEM", "系统规则：不得扩大授权。"));
    messages.add(new ContextMessage("USER", "任务目标：核对出口。" + " 约束 ".repeat(20)));
    // 超长工具输出触发 L1，摘录预览会包含这段正文。
    messages.add(
        new ContextMessage(
            "ASSISTANT", "先读工具输出，key=" + SECRET + "，header " + BEARER));
    messages.add(
        new ContextMessage(
            "TOOL",
            "read_file",
            "line " + ("key=" + SECRET + " header=" + BEARER + " ").repeat(400),
            "tool-1"));
    var breakdown =
        compactor
            .evaluateAndCompact(
                new CompactionRequest(messages, 200, 2, 1, 0.60, 0.78, 0.92))
            .breakdown();

    assertFalse(breakdown.retainedExcerptPreview().contains(SECRET), "摘录预览不得含明文 Key");
    assertFalse(breakdown.retainedExcerptPreview().contains(BEARER), "摘录预览不得含明文令牌");
    assertFalse(breakdown.structuredAnchors().contains(SECRET));
    assertTrue(
        breakdown.retainedExcerptPreview().contains("***MASKED")
            || breakdown.retainedExcerptPreview().isBlank(),
        breakdown.retainedExcerptPreview());
  }

  @Test
  void externalTraceNamesAndSourcesAreMaskedInPersistenceAndReadback() {
    var runs = mock(dev.workbench.persistence.repository.ExternalTraceRunRepository.class);
    var spans = mock(dev.workbench.persistence.repository.ExternalTraceSpanRepository.class);
    var scopes = mock(ExecutionScopeResolver.class);
    when(scopes.current()).thenReturn(ExecutionScope.LOCAL);
    var json = new com.fasterxml.jackson.databind.ObjectMapper();
    var service = new ExternalTraceService(runs, spans, scopes, json);

    var stored = mock(dev.workbench.persistence.entity.ExternalTraceRunEntity.class);
    when(runs.saveAndFlush(org.mockito.ArgumentMatchers.any())).thenReturn(stored);
    var created =
        service.create(
            new ExternalTraceService.CreateRequest(
                "langfuse key=" + SECRET, "Bearer " + BEARER));
    assertFalse(created.name().contains(SECRET), created.name());
    assertFalse(created.source().contains(BEARER), created.source());

    when(runs.findByIdAndOwnerScopeKey(created.id(), ExecutionScope.LOCAL.ownerScopeKey()))
        .thenReturn(java.util.Optional.of(stored));
    when(stored.getId()).thenReturn(created.id());
    when(stored.getName()).thenReturn(created.name());
    when(stored.getSource()).thenReturn(created.source());
    when(stored.getCreatedAt()).thenReturn("2026-01-01T00:00:00Z");
    when(spans.countByRunId(created.id())).thenReturn(0);

    service.append(
        created.id(),
        List.of(
            new ExternalTraceService.SpanInput(
                "NODE",
                "node key=" + SECRET,
                null,
                "COMPLETED",
                null,
                null,
                Map.of("note", BEARER))));
    var saved = org.mockito.ArgumentCaptor.forClass(List.class);
    org.mockito.Mockito.verify(spans).saveAll(saved.capture());
    var entity = (dev.workbench.persistence.entity.ExternalTraceSpanEntity) saved.getValue().getFirst();
    assertFalse(entity.getName().contains(SECRET), "Span 名称不得含明文 Key：" + entity.getName());
    assertFalse(entity.getAttributesJson().contains(BEARER), entity.getAttributesJson());
  }
}
