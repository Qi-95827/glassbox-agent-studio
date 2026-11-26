package dev.workbench.timeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.workbench.framework.trace.SensitiveDataMasker;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 把一次真实运行导出成标准 Trace。
 *
 * <p>数据源就是 V4 的真实时间轴 Span，不重新推断：导出只做格式转换与脱敏，不补 Span、不改顺序。
 * OTel 输出按 OpenTelemetry 的 resourceSpans/scopeSpans/spans 结构，能被 Jaeger / Phoenix / Langfuse
 * 这类工具直接吃下；Glass-Box 输出保留工作台自己的字段语义。
 */
@Service
public class TraceExportService {
  static final String SCOPE_NAME = "dev.workbench.glassbox";
  static final String SCOPE_VERSION = "1";

  private final RunObservationService observations;
  private final ObjectMapper json;

  public TraceExportService(RunObservationService observations, ObjectMapper json) {
    this.observations = observations;
    this.json = json;
  }

  /** 导出入口；未知格式按 Glass-Box 处理，避免静默返回空文档。 */
  public Map<String, Object> export(String runId, String format) {
    var timeline = observations.timeline(runId);
    if ("otel".equalsIgnoreCase(format)) return otel(runId, timeline);
    return glassbox(runId, timeline);
  }

  private Map<String, Object> glassbox(String runId, RunObservationService.Timeline timeline) {
    var document = new LinkedHashMap<String, Object>();
    document.put("schemaVersion", 1);
    document.put("format", "glassbox");
    document.put("runId", runId);
    document.put("exportedAt", Instant.now().toString());
    document.put("summary", timeline.summary());
    document.put("spans", timeline.spans());
    document.put("limitations", timeline.limitations());
    return document;
  }

  /** OTel Span：时间戳用纳秒，缺起点的 Span 不能出现在 OTel 里，改为在 limitations 记一条。 */
  private Map<String, Object> otel(String runId, RunObservationService.Timeline timeline) {
    String traceId = hash(runId, 32);
    var spans = new ArrayList<Map<String, Object>>();
    int skipped = 0;
    for (var span : timeline.spans()) {
      if (span.startMs() == null) {
        skipped += 1;
        continue;
      }
      long startNanos = span.startMs() * 1_000_000L;
      long endNanos = (span.endMs() == null ? span.startMs() : span.endMs()) * 1_000_000L;
      var attributes = new ArrayList<Map<String, Object>>();
      attribute(attributes, "workbench.kind", span.kind());
      attribute(attributes, "workbench.status", span.status());
      attribute(attributes, "workbench.node.key", span.nodeKey());
      attribute(attributes, "workbench.node.id", span.nodeId());
      for (var entry : span.metrics().entrySet())
        attribute(attributes, "workbench.metric." + entry.getKey(), String.valueOf(entry.getValue()));
      var item = new LinkedHashMap<String, Object>();
      item.put("traceId", traceId);
      item.put("spanId", hash(span.id(), 16));
      if (span.nodeId() != null) item.put("parentSpanId", hash("node:" + span.nodeId(), 16));
      item.put(
          "name",
          mask(
              "dev.workbench."
                  + span.kind().toLowerCase(Locale.ROOT)
                  + "."
                  + (span.label() == null ? span.kind().toLowerCase(Locale.ROOT) : span.label())));
      item.put("kind", otelKind(span.kind()));
      item.put("startTimeUnixNano", Long.toString(startNanos));
      item.put("endTimeUnixNano", Long.toString(Math.max(endNanos, startNanos)));
      item.put("attributes", attributes);
      item.put("status", Map.of("code", otelStatus(span.status())));
      spans.add(item);
    }
    var scope = new LinkedHashMap<String, Object>();
    scope.put("name", SCOPE_NAME);
    scope.put("version", SCOPE_VERSION);
    scope.put("spans", spans);

    var resource = new LinkedHashMap<String, Object>();
    var resourceAttributes = new ArrayList<Map<String, Object>>();
    attribute(resourceAttributes, "service.name", "agent-workbench");
    attribute(resourceAttributes, "workbench.run.id", runId);
    resource.put("attributes", resourceAttributes);

    var resourceSpan = new LinkedHashMap<String, Object>();
    resourceSpan.put("resource", resource);
    resourceSpan.put("scopeSpans", List.of(scope));

    var document = new LinkedHashMap<String, Object>();
    document.put("resourceSpans", List.of(resourceSpan));

    var limitations = new ArrayList<>(timeline.limitations());
    if (skipped > 0)
      limitations.add("有 " + skipped + " 条记录缺少开始时间，OpenTelemetry 需要起止时间，因此未导出。");
    document.put("_limitations", List.copyOf(limitations));
    return document;
  }

  /** OTel SpanKind：模型与工具是 CLIENT(3)，其余是 INTERNAL(1)。 */
  private static int otelKind(String kind) {
    return switch (kind) {
      case "MODEL", "TOOL" -> 3;
      default -> 1;
    };
  }

  /** OTel StatusCode：失败类状态记 ERROR(2)，其余记 UNSET(0)。 */
  private static int otelStatus(String status) {
    if (status == null) return 0;
    String upper = status.toUpperCase(Locale.ROOT);
    return upper.contains("FAIL")
            || upper.contains("ERROR")
            || upper.contains("DENIED")
            || upper.contains("REJECT")
            || upper.contains("CANCEL")
        ? 2
        : 0;
  }

  private static void attribute(List<Map<String, Object>> target, String key, String value) {
    if (value == null || value.isBlank()) return;
    target.add(Map.of("key", key, "value", Map.of("stringValue", mask(value))));
  }

  private static String mask(String value) {
    return SensitiveDataMasker.maskAndTruncate(value, 1024).text();
  }

  private static String hash(String value, int length) {
    try {
      var digest =
          MessageDigest.getInstance("SHA-256")
              .digest(String.valueOf(value).getBytes(StandardCharsets.UTF_8));
      String hex = HexFormat.of().formatHex(digest);
      return hex.substring(0, Math.min(length, hex.length()));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("导出摘要不可用。", e);
    }
  }

  /** 供控制器校验运行存在；导出本身不写任何记录。 */
  void requireRun(String runId) {
    observations.timeline(runId);
  }

  String toJson(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException("Trace 无法编码。", e);
    }
  }
}
