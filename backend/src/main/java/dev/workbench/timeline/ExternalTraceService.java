package dev.workbench.timeline;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.workbench.domain.ExecutionScopeResolver;
import dev.workbench.framework.trace.SensitiveDataMasker;
import dev.workbench.persistence.entity.ExternalTraceRunEntity;
import dev.workbench.persistence.entity.ExternalTraceSpanEntity;
import dev.workbench.persistence.repository.ExternalTraceRunRepository;
import dev.workbench.persistence.repository.ExternalTraceSpanRepository;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.stereotype.Service;

/**
 * 外部 Agent 框架的轻量 Trace 接入。
 *
 * <p>只做「收、洗、存、读」四件事：严格绑定当前登录账号的归属键（跨账号一律按不存在处理），单次 Span
 * 数与载荷体积都有硬上限，任何文本都先经 {@link SensitiveDataMasker} 清洗再落库。外部 Trace 不参与
 * 计划、授权、检查点、恢复或分叉，只能在只读透视里查看。
 */
@Service
public class ExternalTraceService {
  static final int MAX_SPANS_PER_REQUEST = 200;
  static final int MAX_SPANS_PER_RUN = 2000;
  static final int MAX_NAME = 200;
  static final int MAX_VALUE_CHARS = 2000;
  static final int MAX_ATTRIBUTE_KEYS = 32;
  static final Set<String> KINDS =
      Set.of("NODE", "MODEL", "TOOL", "CONTEXT", "HANDOFF", "CHECKPOINT", "OTHER");

  private final ExternalTraceRunRepository runs;
  private final ExternalTraceSpanRepository spans;
  private final ExecutionScopeResolver scopes;
  private final ObjectMapper json;

  public ExternalTraceService(
      ExternalTraceRunRepository runs,
      ExternalTraceSpanRepository spans,
      ExecutionScopeResolver scopes,
      ObjectMapper json) {
    this.runs = runs;
    this.spans = spans;
    this.scopes = scopes;
    this.json = json;
  }

  public record CreateRequest(String name, String source) {}

  public record SpanInput(
      String kind,
      String name,
      String nodeKey,
      String status,
      String startedAt,
      String endedAt,
      Map<String, Object> attributes) {}

  public record SpanView(
      String id,
      String kind,
      String name,
      String nodeKey,
      String status,
      String startedAt,
      String endedAt,
      Map<String, Object> attributes) {}

  public record RunView(
      String id, String name, String source, String createdAt, int spanCount) {}

  public record RunDetail(RunView run, List<SpanView> spans, List<String> limitations) {}

  public record AppendResult(String runId, int accepted, int total) {}

  public RunView create(CreateRequest request) {
    String owner = scopes.current().ownerScopeKey();
    String name = text(request == null ? null : request.name(), MAX_NAME, "外部接入 Trace");
    String source = text(request == null ? null : request.source(), 80, "external");
    var row =
        new ExternalTraceRunEntity(
            UUID.randomUUID().toString(), owner, name, source, Instant.now().toString());
    runs.saveAndFlush(row);
    return new RunView(row.getId(), row.getName(), row.getSource(), row.getCreatedAt(), 0);
  }

  public List<RunView> list() {
    String owner = scopes.current().ownerScopeKey();
    return runs.findByOwnerScopeKeyOrderByCreatedAtDesc(owner).stream()
        .map(row -> new RunView(row.getId(), row.getName(), row.getSource(), row.getCreatedAt(),
            spans.countByRunId(row.getId())))
        .toList();
  }

  public RunDetail get(String runId) {
    var run = requireOwned(runId);
    var listed =
        spans.findByRunIdOrderBySeqAsc(runId).stream()
            .map(
                row ->
                    new SpanView(
                        row.getId(),
                        row.getKind(),
                        row.getName(),
                        row.getNodeKey(),
                        row.getStatus(),
                        row.getStartedAt(),
                        row.getEndedAt(),
                        readAttributes(row.getAttributesJson())))
            .toList();
    var limitations = new ArrayList<String>();
    limitations.add("外部 Trace 没有计划、工具授权与检查点，因此只能只读透视，不能恢复、分叉或重新执行。");
    if (listed.size() < spans.countByRunId(runId))
      limitations.add("返回的 Span 少于已存条数，请刷新后重试。");
    return new RunDetail(
        new RunView(run.getId(), run.getName(), run.getSource(), run.getCreatedAt(), listed.size()),
        listed,
        List.copyOf(limitations));
  }

  public AppendResult append(String runId, List<SpanInput> inputs) {
    requireOwned(runId);
    if (inputs == null || inputs.isEmpty()) throw new IllegalArgumentException("至少需要一条 Span。");
    if (inputs.size() > MAX_SPANS_PER_REQUEST)
      throw new IllegalArgumentException("单次最多提交 " + MAX_SPANS_PER_REQUEST + " 条 Span。");
    int existing = spans.countByRunId(runId);
    if (existing + inputs.size() > MAX_SPANS_PER_RUN)
      throw new IllegalArgumentException("单个外部 Trace 最多保存 " + MAX_SPANS_PER_RUN + " 条 Span。");
    String now = Instant.now().toString();
    int seq = existing;
    var rows = new ArrayList<ExternalTraceSpanEntity>();
    for (var input : inputs) {
      seq += 1;
      rows.add(span(runId, seq, input, now));
    }
    spans.saveAll(rows);
    return new AppendResult(runId, rows.size(), existing + rows.size());
  }

  private ExternalTraceSpanEntity span(String runId, int seq, SpanInput input, String now) {
    if (input == null) throw new IllegalArgumentException("Span 不能为空。");
    String kind = text(input.kind(), 32, "OTHER").toUpperCase(Locale.ROOT);
    if (!KINDS.contains(kind))
      throw new IllegalArgumentException("不支持的 Span 类型：" + input.kind() + "。");
    String name = text(input.name(), MAX_NAME, null);
    if (name == null) throw new IllegalArgumentException("Span 需要名称。");
    return new ExternalTraceSpanEntity(
        UUID.randomUUID().toString(),
        runId,
        seq,
        kind,
        name,
        text(input.nodeKey(), 64, null),
        text(input.status(), 32, null),
        instant(input.startedAt()),
        instant(input.endedAt()),
        writeAttributes(input.attributes()),
        now);
  }

  /** 归属不符与不存在同样返回 404，避免用错误码探测别人的 Trace。 */
  private ExternalTraceRunEntity requireOwned(String runId) {
    String owner = scopes.current().ownerScopeKey();
    return runs
        .findByIdAndOwnerScopeKey(runId, owner)
        .orElseThrow(() -> new EmptyResultDataAccessException(1));
  }

  private String writeAttributes(Map<String, Object> attributes) {
    if (attributes == null || attributes.isEmpty()) return null;
    if (attributes.size() > MAX_ATTRIBUTE_KEYS)
      throw new IllegalArgumentException("单个 Span 最多 " + MAX_ATTRIBUTE_KEYS + " 个属性。");
    var masked = new LinkedHashMap<String, Object>();
    for (var entry : attributes.entrySet()) {
      String key = SensitiveDataMasker.maskAndTruncate(entry.getKey(), 120).text();
      masked.put(key, clean(entry.getValue()));
    }
    try {
      return json.writeValueAsString(masked);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("Span 属性无法序列化。");
    }
  }

  private Map<String, Object> readAttributes(String raw) {
    if (raw == null || raw.isBlank()) return Map.of();
    try {
      return json.readValue(
          raw,
          json.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, Object.class));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("已保存的 Span 属性无法读取。", e);
    }
  }

  /** 属性值允许标量或字符串化的复杂值；一律脱敏 + 截断，不保留原始密钥形态。 */
  private static Object clean(Object value) {
    if (value == null) return "";
    if (value instanceof Number || value instanceof Boolean) return value;
    String text =
        value instanceof String string ? string : value.toString();
    return SensitiveDataMasker.maskAndTruncate(text, MAX_VALUE_CHARS).text();
  }

  private static String text(String value, int limit, String fallback) {
    if (value == null || value.isBlank()) return fallback;
    String trimmed = value.strip();
    return trimmed.length() > limit ? trimmed.substring(0, limit) : trimmed;
  }

  /** 时间戳只接受可解析的 ISO-8601；非法输入直接拒绝，不落一个无法排序的字符串。 */
  private static String instant(String value) {
    if (value == null || value.isBlank()) return null;
    try {
      return Instant.parse(value.strip()).toString();
    } catch (DateTimeParseException e) {
      throw new IllegalArgumentException("时间戳必须是 ISO-8601，例如 2026-11-26T09:00:00Z。");
    }
  }
}
