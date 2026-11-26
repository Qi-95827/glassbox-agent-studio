package dev.workbench.timeline;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 导出只做格式转换与脱敏：OTel 必须含起止时间，缺时间戳的 Span 不得伪造时间混进去。 */
class TraceExportServiceTest {
  private final RunObservationService observations = mock(RunObservationService.class);
  private final String runId = "11111111-2222-3333-4444-555555555555";

  private TraceExportService service;

  @BeforeEach
  void setUp() {
    service = new TraceExportService(observations, new ObjectMapper());
  }

  @Test
  void otelExportCarriesResourceScopeAndRealSpanTimes() {
    when(observations.timeline(runId)).thenReturn(timeline(List.of(modelSpan())));
    Map<String, Object> document = service.export(runId, "otel");

    @SuppressWarnings("unchecked")
    var resourceSpans = (List<Map<String, Object>>) document.get("resourceSpans");
    assertEquals(1, resourceSpans.size());
    @SuppressWarnings("unchecked")
    var resource = (Map<String, Object>) resourceSpans.getFirst().get("resource");
    assertTrue(resource.get("attributes").toString().contains("agent-workbench"));

    @SuppressWarnings("unchecked")
    var scopeSpans = (List<Map<String, Object>>) resourceSpans.getFirst().get("scopeSpans");
    @SuppressWarnings("unchecked")
    var spans = (List<Map<String, Object>>) scopeSpans.getFirst().get("spans");
    assertEquals(1, spans.size());
    var span = spans.getFirst();
    assertEquals(32, String.valueOf(span.get("traceId")).length());
    assertEquals(16, String.valueOf(span.get("spanId")).length());
    // 模型调用在 OTel 里是 CLIENT(3)，与内部 Span 区分。
    assertEquals(3, span.get("kind"));
    assertEquals(1_000_000_000L, Long.parseLong(String.valueOf(span.get("startTimeUnixNano"))));
    assertEquals(2_000_000_000L, Long.parseLong(String.valueOf(span.get("endTimeUnixNano"))));
    assertEquals(Map.of("code", 0), span.get("status"));
  }

  @Test
  void spanWithoutStartTimeIsSkippedWithAnExplicitLimitation() {
    when(observations.timeline(runId))
        .thenReturn(
            timeline(
                List.of(
                    new RunObservationService.Span(
                        "compaction:x", "COMPACTION", null, null, "L2", null, null, null, null,
                        null, Map.of()))));
    Map<String, Object> document = service.export(runId, "otel");
    @SuppressWarnings("unchecked")
    var resourceSpans = (List<Map<String, Object>>) document.get("resourceSpans");
    @SuppressWarnings("unchecked")
    var scopeSpans = (List<Map<String, Object>>) resourceSpans.getFirst().get("scopeSpans");
    assertTrue(((List<?>) scopeSpans.getFirst().get("spans")).isEmpty());
    assertTrue(String.valueOf(document.get("_limitations")).contains("缺少开始时间"));
  }

  @Test
  void glassboxExportKeepsRunSemanticsAndMasksSensitiveText() {
    when(observations.timeline(runId)).thenReturn(timeline(List.of(modelSpan())));
    Map<String, Object> document = service.export(runId, "anything-else");
    assertEquals("glassbox", document.get("format"));
    assertEquals(runId, document.get("runId"));
    assertEquals(1, ((List<?>) document.get("spans")).size());
    assertTrue(document.containsKey("limitations"));
  }

  @Test
  void failingSpanIsExportedAsOtelErrorStatus() {
    when(observations.timeline(runId))
        .thenReturn(
            timeline(
                List.of(
                    new RunObservationService.Span(
                        "tool:t1", "TOOL", "n1", "analyze", "workspace_write", "FAILED", null, null,
                        1000L, 1500L, Map.of()))));
    var spans = otelSpans(service.export(runId, "otel"));
    assertEquals(Map.of("code", 2), spans.getFirst().get("status"));
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> otelSpans(Map<String, Object> document) {
    var resourceSpans = (List<Map<String, Object>>) document.get("resourceSpans");
    var scopeSpans = (List<Map<String, Object>>) resourceSpans.getFirst().get("scopeSpans");
    return (List<Map<String, Object>>) scopeSpans.getFirst().get("spans");
  }

  private static RunObservationService.Span modelSpan() {
    return new RunObservationService.Span(
        "model:m1",
        "MODEL",
        "n1",
        "analyze",
        "deepseek-chat",
        "COMPLETED",
        "1970-01-01T00:00:01Z",
        "1970-01-01T00:00:02Z",
        1000L,
        2000L,
        Map.of("inputTokens", 10, "outputTokens", 5));
  }

  private static RunObservationService.Timeline timeline(List<RunObservationService.Span> spans) {
    return new RunObservationService.Timeline(
        1,
        "run",
        new RunObservationService.RunSummary("COMPLETED", 2000L, 1, 1, 1, 0, 10L, 5L, 1, 1),
        spans,
        List.of());
  }
}
