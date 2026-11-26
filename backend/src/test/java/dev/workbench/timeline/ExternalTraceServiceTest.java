package dev.workbench.timeline;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.workbench.domain.ExecutionScope;
import dev.workbench.domain.ExecutionScopeResolver;
import dev.workbench.persistence.entity.ExternalTraceRunEntity;
import dev.workbench.persistence.entity.ExternalTraceSpanEntity;
import dev.workbench.persistence.repository.ExternalTraceRunRepository;
import dev.workbench.persistence.repository.ExternalTraceSpanRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.EmptyResultDataAccessException;

/** 外部 Trace：归属优先、上限硬、落库前脱敏，跨账号一律按不存在处理。 */
class ExternalTraceServiceTest {
  private final ExternalTraceRunRepository runs = mock(ExternalTraceRunRepository.class);
  private final ExternalTraceSpanRepository spans = mock(ExternalTraceSpanRepository.class);
  private final ExecutionScopeResolver scopes = mock(ExecutionScopeResolver.class);

  private ExternalTraceService service;

  @BeforeEach
  void setUp() {
    service = new ExternalTraceService(runs, spans, scopes, new ObjectMapper());
    when(scopes.current()).thenReturn(ExecutionScope.LOCAL);
    when(spans.countByRunId(anyString())).thenReturn(0);
    when(spans.saveAll(any())).thenReturn(List.of());
  }

  @Test
  void createBindsTheCurrentOwnerKeyAndTrimsTheName() {
    when(runs.saveAndFlush(any()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    var view = service.create(new ExternalTraceService.CreateRequest("  my-agent  ", "python"));
    assertEquals("my-agent", view.name());
    assertEquals("python", view.source());
    var saved = (ExternalTraceRunEntity) org.mockito.Mockito.mockingDetails(runs).getInvocations()
        .stream()
        .filter(invocation -> invocation.getMethod().getName().equals("saveAndFlush"))
        .findFirst()
        .orElseThrow()
        .getArgument(0);
    assertEquals(ExecutionScope.LOCAL.ownerScopeKey(), saved.getOwnerScopeKey());
  }

  @Test
  void rejectsUnknownSpanKindAndOversizedBatches() {
    when(runs.findByIdAndOwnerScopeKey("run-1", ExecutionScope.LOCAL.ownerScopeKey()))
        .thenReturn(Optional.of(run("run-1")));
    assertTrue(
        assertThrows(
                IllegalArgumentException.class,
                () ->
                    service.append(
                        "run-1",
                        List.of(
                            new ExternalTraceService.SpanInput(
                                "SECRETS", "x", null, null, null, null, Map.of()))))
            .getMessage()
            .contains("不支持的 Span 类型"));
    var tooMany = new java.util.ArrayList<ExternalTraceService.SpanInput>();
    for (int index = 0; index < ExternalTraceService.MAX_SPANS_PER_REQUEST + 1; index += 1)
      tooMany.add(
          new ExternalTraceService.SpanInput("NODE", "n" + index, null, null, null, null, Map.of()));
    assertTrue(
        assertThrows(IllegalArgumentException.class, () -> service.append("run-1", tooMany))
            .getMessage()
            .contains("单次最多"));
  }

  @Test
  void crossAccountAccessIsIndistinguishableFromMissing() {
    when(runs.findByIdAndOwnerScopeKey(anyString(), anyString())).thenReturn(Optional.empty());
    assertThrows(EmptyResultDataAccessException.class, () -> service.get("other-run"));
    assertThrows(
        EmptyResultDataAccessException.class,
        () ->
            service.append(
                "other-run",
                List.of(
                    new ExternalTraceService.SpanInput(
                        "NODE", "n1", null, null, null, null, Map.of()))));
    verify(spans, never()).saveAll(any());
  }

  @Test
  void attributesAreMaskedBeforeTheyReachTheDatabase() {
    when(runs.findByIdAndOwnerScopeKey("run-1", ExecutionScope.LOCAL.ownerScopeKey()))
        .thenReturn(Optional.of(run("run-1")));
    service.append(
        "run-1",
        List.of(
            new ExternalTraceService.SpanInput(
                "MODEL",
                "call",
                "analyze",
                "COMPLETED",
                "2026-11-26T09:00:00Z",
                "2026-11-26T09:00:01Z",
                Map.of("authorization", "Bearer sk-abcdefghijklmnop", "tokens", 42))));
    @SuppressWarnings("unchecked")
    List<ExternalTraceSpanEntity> saved =
        (List<ExternalTraceSpanEntity>)
            org.mockito.Mockito.mockingDetails(spans).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("saveAll"))
                .findFirst()
                .orElseThrow()
                .getArgument(0);
    var row = saved.getFirst();
    assertEquals("MODEL", row.getKind());
    assertEquals(1, row.getSeq());
    assertTrue(row.getAttributesJson().contains("42"), "非敏感值保持原样");
    assertFalse(
        row.getAttributesJson().contains("sk-abcdefghijklmnop"), "凭据形态的文本必须被脱敏");
  }

  @Test
  void rejectsNonIsoTimestampsInsteadOfStoringUnsortedStrings() {
    when(runs.findByIdAndOwnerScopeKey("run-1", ExecutionScope.LOCAL.ownerScopeKey()))
        .thenReturn(Optional.of(run("run-1")));
    assertTrue(
        assertThrows(
                IllegalArgumentException.class,
                () ->
                    service.append(
                        "run-1",
                        List.of(
                            new ExternalTraceService.SpanInput(
                                "NODE", "n1", null, null, "yesterday", null, Map.of()))))
            .getMessage()
            .contains("ISO-8601"));
  }

  private static ExternalTraceRunEntity run(String id) {
    return new ExternalTraceRunEntity(
        id, ExecutionScope.LOCAL.ownerScopeKey(), "demo", "python", "2026-11-26T09:00:00Z");
  }
}
