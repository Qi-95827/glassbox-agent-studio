package dev.workbench.store;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.workbench.framework.context.ContextBreakdown;
import dev.workbench.framework.trace.GlassboxModels.GatewayCheck;
import dev.workbench.framework.trace.GlassboxModels.ModelCallPayload;
import dev.workbench.framework.trace.GlassboxModels.PromptMessage;
import dev.workbench.framework.trace.GlassboxModels.ToolCallDecision;
import dev.workbench.framework.trace.GlassboxModels.ToolCallTrace;
import dev.workbench.framework.trace.GlassboxModels.ToolSchemaView;
import dev.workbench.framework.trace.SensitiveDataMasker;
import dev.workbench.persistence.entity.ModelCallPayloadEntity;
import dev.workbench.persistence.entity.ToolCallTraceEntity;
import dev.workbench.persistence.entity.WorkflowPlanControlEntity;
import dev.workbench.persistence.repository.ModelCallPayloadRepository;
import dev.workbench.persistence.repository.ToolCallTraceRepository;
import dev.workbench.persistence.repository.WorkflowPlanControlRepository;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 玻璃盒透视快照与运行断点控制的持久化服务。 */
@Service
public class GlassboxTraceStore {
  public static final String MODE_STEP_TEACHING = "STEP_TEACHING";
  public static final String MODE_PARALLEL = "PARALLEL";

  private final ModelCallPayloadRepository modelPayloads;
  private final ToolCallTraceRepository toolTraces;
  private final WorkflowPlanControlRepository planControls;
  private final ObjectMapper json;

  public GlassboxTraceStore(
      ModelCallPayloadRepository modelPayloads,
      ToolCallTraceRepository toolTraces,
      WorkflowPlanControlRepository planControls,
      ObjectMapper json) {
    this.modelPayloads = modelPayloads;
    this.toolTraces = toolTraces;
    this.planControls = planControls;
    this.json = json;
  }

  public record PlanControl(String executionMode, Set<String> breakpoints) {
    public PlanControl {
      executionMode =
          MODE_PARALLEL.equalsIgnoreCase(executionMode) ? MODE_PARALLEL : MODE_STEP_TEACHING;
      breakpoints = breakpoints == null ? Set.of() : Set.copyOf(breakpoints);
    }
  }

  @Transactional
  public void recordModelPayload(String runId, String modelCallId, ModelCallPayload payload) {
    if (runId == null || modelCallId == null || payload == null) return;
    var listTruncated = new boolean[1];
    // 先在字段层脱敏再序列化 JSON 数组，防止单个字段中的未闭合 PEM 吞掉数组闭合符和后续条目。
    var reqMessagesJson = writeListWithinLimit(maskMessages(payload.requestMessages()), listTruncated);
    var reqToolsJson = writeListWithinLimit(maskTools(payload.requestTools()), listTruncated);
    var respContent = SensitiveDataMasker.maskAndTruncate(payload.responseContent());
    var respToolsJson = writeListWithinLimit(maskToolCalls(payload.responseToolCalls()), listTruncated);
    // 思考链同样是模型输出，必须和正文一样脱敏后再落库。
    var reasoning = SensitiveDataMasker.maskAndTruncate(payload.reasoningContent());
    boolean truncated =
        payload.truncated()
            || listTruncated[0]
            || respContent.truncated()
            || reasoning.truncated();
    modelPayloads.save(
        new ModelCallPayloadEntity(
            modelCallId,
            runId,
            reqMessagesJson,
            reqToolsJson,
            respContent.text(),
            respToolsJson,
            truncated,
            payload.context() == null ? null : writeJson(payload.context()),
            payload.context() == null ? null : payload.context().appliedTier().name(),
            Instant.now().toString(),
            reasoning.text(),
            payload.streamed()));
  }

  @Transactional(readOnly = true)
  public Map<String, ModelCallPayload> modelPayloadsByRun(String runId) {
    var result = new LinkedHashMap<String, ModelCallPayload>();
    for (var row : modelPayloads.findByRunId(runId)) {
      result.put(row.getModelCallId(), toPayload(row));
    }
    return result;
  }

  /**
   * 单节点按需下推：只读取指定调用 ID 的模型载荷。
   *
   * <p>单节点详情此前调用 {@link #modelPayloadsByRun} 拉整次运行的全部 64KB 级载荷，节点越多浪费越大；
   * 这里按调用 ID 精确下推，并按 {@value #QUERY_CHUNK} 分片避免超出数据库 IN 参数上限。
   * 空集合直接返回空映射，不产生任何查询。
   */
  @Transactional(readOnly = true)
  public Map<String, ModelCallPayload> modelPayloadsByIds(
      String runId, java.util.Collection<String> modelCallIds) {
    if (runId == null || modelCallIds == null || modelCallIds.isEmpty()) return Map.of();
    var result = new LinkedHashMap<String, ModelCallPayload>();
    for (var chunk : chunks(modelCallIds)) {
      for (var row : modelPayloads.findByRunIdAndModelCallIdIn(runId, chunk)) {
        result.put(row.getModelCallId(), toPayload(row));
      }
    }
    return result;
  }

  private ModelCallPayload toPayload(ModelCallPayloadEntity row) {
    return new ModelCallPayload(
        readList(row.getRequestMessagesJson(), new TypeReference<List<PromptMessage>>() {}),
        readList(row.getRequestToolsJson(), new TypeReference<List<ToolSchemaView>>() {}),
        row.getResponseContent(),
        readList(row.getResponseToolCallsJson(), new TypeReference<List<ToolCallDecision>>() {}),
        row.isTruncated(),
        readContext(row.getContextBreakdownJson()),
        row.getReasoningContent(),
        row.isStreamed());
  }

  /** 数据库 IN 参数安全分片大小。 */
  static final int QUERY_CHUNK = 500;

  private static List<List<String>> chunks(java.util.Collection<String> ids) {
    var distinct =
        ids.stream().filter(java.util.Objects::nonNull).distinct().toList();
    var result = new java.util.ArrayList<List<String>>();
    for (int start = 0; start < distinct.size(); start += QUERY_CHUNK) {
      result.add(distinct.subList(start, Math.min(distinct.size(), start + QUERY_CHUNK)));
    }
    return result;
  }

  /**
   * 列表类载荷的结构化裁剪：只删尾部条目，保证序列化结果永远是合法 JSON 数组。
   *
   * <p>旧实现先 `writeJson` 再按字符截断，会把数组或对象从中间切开，落库后无法反序列化。
   */
  private <T> String writeListWithinLimit(List<T> items, boolean[] truncatedFlag) {
    var kept = new java.util.ArrayList<T>(items == null ? List.of() : items);
    String encoded = writeJson(kept);
    while (encoded.length() > SensitiveDataMasker.DEFAULT_MAX_CHARS && !kept.isEmpty()) {
      kept.remove(kept.size() - 1);
      encoded = writeJson(kept);
    }
    if (encoded.length() > SensitiveDataMasker.DEFAULT_MAX_CHARS) {
      if (truncatedFlag != null) truncatedFlag[0] = true;
      return "[]";
    }
    if (kept.size() != (items == null ? 0 : items.size()) && truncatedFlag != null)
      truncatedFlag[0] = true;
    // 调用方已在字段层完成脱敏；此处直接返回合法 JSON 数组，不再对编码后的 JSON 字符串整段做正则替换。
    return encoded;
  }

  private static List<PromptMessage> maskMessages(List<PromptMessage> items) {
    if (items == null || items.isEmpty()) return List.of();
    return items.stream()
        .map(
            item ->
                new PromptMessage(
                    maskField(item.role()),
                    maskField(item.name()),
                    maskField(item.content()),
                    item.charCount()))
        .toList();
  }

  private List<ToolSchemaView> maskTools(List<ToolSchemaView> items) {
    if (items == null || items.isEmpty()) return List.of();
    return items.stream()
        .map(
            item ->
                new ToolSchemaView(
                    maskField(item.name()),
                    maskField(item.description()),
                    maskJsonOrText(item.parametersJson())))
        .toList();
  }

  private List<ToolCallDecision> maskToolCalls(List<ToolCallDecision> items) {
    if (items == null || items.isEmpty()) return List.of();
    return items.stream()
        .map(
            item ->
                new ToolCallDecision(
                    maskField(item.id()),
                    maskField(item.name()),
                    maskJsonOrText(item.argumentsJson())))
        .toList();
  }

  private static List<GatewayCheck> maskGatewayChecks(List<GatewayCheck> items) {
    if (items == null || items.isEmpty()) return List.of();
    return items.stream()
        .map(
            item ->
                new GatewayCheck(
                    maskField(item.gate()),
                    maskField(item.label()),
                    maskField(item.status()),
                    maskField(item.detail())))
        .toList();
  }

  private static String maskField(String value) {
    return value == null ? null : SensitiveDataMasker.mask(value);
  }

  /**
   * 载荷字段的内层 JSON 叶子级脱敏与结构保真限长：与 {@link dev.workbench.framework.trace.JsonLeafMasker}
   * 共享同一实现，保证生产端与落库端对未闭合 PEM、敏感键值及超长字段的口径 100% 一致。
   */
  private String maskJsonOrText(String value) {
    return dev.workbench.framework.trace.JsonLeafMasker.maskAndTruncate(
            json, value, SensitiveDataMasker.DEFAULT_MAX_CHARS)
        .text();
  }

  @Transactional
  public void recordToolTrace(String runId, String toolCallId, ToolCallTrace trace) {
    if (runId == null || toolCallId == null || trace == null) return;
    // 工具入参常常是 JSON 字符串：在叶子层脱敏并在树内限长，保证合法 JSON 不被整段正则或硬切破坏。
    var args =
        dev.workbench.framework.trace.JsonLeafMasker.maskAndTruncate(
            json, trace.inputArgs(), SensitiveDataMasker.DEFAULT_MAX_CHARS);
    var checksJson = writeListWithinLimit(maskGatewayChecks(trace.gatewayChecks()), null);
    var full = SensitiveDataMasker.maskAndTruncate(trace.fullResult());
    boolean truncated = trace.resultTruncated() || args.truncated() || full.truncated();
    toolTraces.save(
        new ToolCallTraceEntity(
            toolCallId,
            runId,
            args.text(),
            checksJson,
            full.text(),
            truncated,
            Instant.now().toString()));
  }

  @Transactional(readOnly = true)
  public Map<String, ToolCallTrace> toolTracesByRun(String runId) {
    var result = new LinkedHashMap<String, ToolCallTrace>();
    for (var row : toolTraces.findByRunId(runId)) {
      result.put(row.getToolCallId(), toTrace(row));
    }
    return result;
  }

  /**
   * 单节点按需下推：只读取指定工具调用 ID 的轨迹；空集合不产生查询，大集合按 {@value #QUERY_CHUNK} 分片。
   */
  @Transactional(readOnly = true)
  public Map<String, ToolCallTrace> toolTracesByIds(
      String runId, java.util.Collection<String> toolCallIds) {
    if (runId == null || toolCallIds == null || toolCallIds.isEmpty()) return Map.of();
    var result = new LinkedHashMap<String, ToolCallTrace>();
    for (var chunk : chunks(toolCallIds)) {
      for (var row : toolTraces.findByRunIdAndToolCallIdIn(runId, chunk)) {
        result.put(row.getToolCallId(), toTrace(row));
      }
    }
    return result;
  }

  private ToolCallTrace toTrace(ToolCallTraceEntity row) {
    return new ToolCallTrace(
        row.getInputArgsJson(),
        readList(row.getGatewayChecksJson(), new TypeReference<List<GatewayCheck>>() {}),
        row.getFullResult(),
        row.isResultTruncated());
  }

  @Transactional(readOnly = true)
  public PlanControl planControl(String runId) {
    return planControls
        .findById(runId)
        .map(
            row ->
                new PlanControl(
                    row.getExecutionMode(),
                    new LinkedHashSet<>(
                        readList(row.getBreakpointsJson(), new TypeReference<List<String>>() {}))))
        .orElseGet(() -> new PlanControl(MODE_STEP_TEACHING, Set.of()));
  }

  @Transactional
  public PlanControl updatePlanControl(
      String runId, String executionMode, CollectionOrNull breakpoints) {
    var existing = planControls.findById(runId).orElse(null);
    String mode =
        executionMode == null
            ? (existing == null ? MODE_STEP_TEACHING : existing.getExecutionMode())
            : (MODE_PARALLEL.equalsIgnoreCase(executionMode) ? MODE_PARALLEL : MODE_STEP_TEACHING);
    Set<String> normalized =
        breakpoints == null
            ? (existing == null
                ? Set.of()
                : new LinkedHashSet<>(
                    readList(existing.getBreakpointsJson(), new TypeReference<List<String>>() {})))
            : sanitizeIds(breakpoints.values());
    String now = Instant.now().toString();
    String jsonText = writeJson(List.copyOf(normalized));
    if (existing == null) {
      planControls.save(new WorkflowPlanControlEntity(runId, mode, jsonText, now));
    } else {
      existing.update(mode, jsonText, now);
      planControls.save(existing);
    }
    return new PlanControl(mode, normalized);
  }

  public record CollectionOrNull(List<String> values) {}

  private static Set<String> sanitizeIds(List<String> values) {
    if (values == null) return Set.of();
    var set = new LinkedHashSet<String>();
    for (String value : values) {
      if (value != null && !value.isBlank()) set.add(value.trim());
    }
    return set;
  }

  private String writeJson(Object value) {
    if (value == null) return "[]";
    try {
      return json.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      return "[]";
    }
  }

  private <T> List<T> readList(String raw, TypeReference<List<T>> type) {
    if (raw == null || raw.isBlank()) return List.of();
    try {
      List<T> parsed = json.readValue(raw, type);
      return parsed == null ? List.of() : parsed;
    } catch (JsonProcessingException e) {
      return List.of();
    }
  }

  private ContextBreakdown readContext(String raw) {
    if (raw == null || raw.isBlank()) return null;
    try {
      return json.readValue(raw, ContextBreakdown.class);
    } catch (JsonProcessingException e) {
      return null;
    }
  }
}
