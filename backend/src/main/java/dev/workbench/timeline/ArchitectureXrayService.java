package dev.workbench.timeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.workbench.domain.RunSnapshot;
import dev.workbench.framework.context.ContextBreakdown;
import dev.workbench.persistence.repository.ModelCallPayloadRepository;
import dev.workbench.store.RunRecordService;
import dev.workbench.task.WorkflowExecutionService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 动态实时架构 X 光：把一次真实运行压成「哪个引擎被真的走到、走了多少次、花了多久」。
 *
 * <p>模块标识与前端架构目录一一对应，前端据此高亮真实流经的模块。所有数字都从已落库的 Span 与
 * 载荷统计，没被走到的模块保持 {@code IDLE} 而不是补 0 假装被走过；压缩节省率取模型调用记录里的
 * 真实比例。
 */
@Service
public class ArchitectureXrayService {
  static final int SCHEMA_VERSION = 1;

  /** 与前端 architectureCatalog 的模块 id 保持一致。 */
  private static final List<String> MODULES =
      List.of(
          "run-lifecycle",
          "agent-assembly",
          "tool-gateway",
          "handoff-envelope",
          "model-byok",
          "delivery-monitoring");

  private final RunObservationService observations;
  private final RunRecordService records;
  private final WorkflowExecutionService workflows;
  private final ModelCallPayloadRepository payloads;
  private final ObjectMapper json;

  public ArchitectureXrayService(
      RunObservationService observations,
      RunRecordService records,
      WorkflowExecutionService workflows,
      ModelCallPayloadRepository payloads,
      ObjectMapper json) {
    this.observations = observations;
    this.records = records;
    this.workflows = workflows;
    this.payloads = payloads;
    this.json = json;
  }

  /** 一步 = 一个可回放的时刻；点击回到运行详情时可定位节点与玻璃盒 Tab。 */
  public record Step(
      String id, long atMs, String kind, String label, String moduleId, String nodeId, String glassboxTab) {}

  public record Module(
      String id,
      String status,
      int callCount,
      long durationMs,
      Integer compactionSavedPercent,
      int gateBlocks,
      List<String> anchorNodes) {}

  public record Summary(
      String status,
      int activeModules,
      int modelCalls,
      int toolCalls,
      int handoffs,
      int checkpoints,
      long inputTokens,
      long outputTokens) {}

  public record Xray(
      int schemaVersion,
      String runId,
      Summary summary,
      List<Module> modules,
      List<Step> steps,
      List<String> limitations) {}

  public Xray xray(String runId) {
    var timeline = observations.timeline(runId);
    var spans = timeline.spans();
    var handoffs = workflows.workflowHandoffs(runId);
    var savedPercent = compactionSavedPercent(runId);

    var byModule = new LinkedHashMap<String, Module>();
    byModule.put(
        "run-lifecycle",
        module("run-lifecycle", spans, List.of("NODE"), savedPercent, 0));
    byModule.put(
        "agent-assembly", module("agent-assembly", spans, List.of("ATTEMPT"), savedPercent, 0));
    byModule.put(
        "tool-gateway", module("tool-gateway", spans, List.of("TOOL", "APPROVAL"), savedPercent, blocks(spans)));
    byModule.put(
        "handoff-envelope",
        new Module(
            "handoff-envelope",
            handoffs.isEmpty() ? "IDLE" : "ACTIVE",
            handoffs.size(),
            0L,
            null,
            0,
            handoffs.stream()
                .map(handoff -> handoff.receiverNodeId() == null ? null : handoff.receiverNodeId().toString())
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList()));
    byModule.put("model-byok", module("model-byok", spans, List.of("MODEL"), savedPercent, 0));
    byModule.put(
        "delivery-monitoring",
        module("delivery-monitoring", spans, List.of("CHECKPOINT"), savedPercent, 0));

    var steps = new ArrayList<Step>();
    for (var span : spans) {
      if (span.startMs() == null) continue;
      steps.add(
          new Step(
              span.id(),
              span.startMs(),
              span.kind(),
              span.label(),
              moduleOf(span.kind()),
              span.nodeId(),
              glassboxTabOf(span.kind())));
    }
    steps.sort(java.util.Comparator.comparingLong(Step::atMs).thenComparing(Step::id));

    var limitations = new ArrayList<>(timeline.limitations());
    limitations.add("模块指标来自已落库的节点、尝试、模型、工具、审批与检查点记录；未走到的模块显示为空闲，不代表它不存在。");
    if (savedPercent == null)
      limitations.add("这次运行没有记录上下文压缩比例，压缩节省率不可用。");

    int active = (int) byModule.values().stream().filter(module -> "ACTIVE".equals(module.status())).count();
    var summary =
        new Summary(
            records.runSnapshot(runId).status().name(),
            active,
            timeline.summary().modelCalls(),
            timeline.summary().toolCalls(),
            handoffs.size(),
            count(spans, "CHECKPOINT"),
            timeline.summary().inputTokens(),
            timeline.summary().outputTokens());
    return new Xray(
        SCHEMA_VERSION,
        runId,
        summary,
        MODULES.stream().map(byModule::get).toList(),
        List.copyOf(steps),
        List.copyOf(limitations));
  }

  private Module module(
      String id,
      List<RunObservationService.Span> spans,
      List<String> kinds,
      Integer saved,
      int blocks) {
    var matching = spans.stream().filter(span -> kinds.contains(span.kind())).toList();
    long duration = 0;
    for (var span : matching) {
      if (span.startMs() != null && span.endMs() != null)
        duration += Math.max(span.endMs() - span.startMs(), 0);
    }
    return new Module(
        id,
        matching.isEmpty() ? "IDLE" : "ACTIVE",
        matching.size(),
        duration,
        "model-byok".equals(id) ? saved : null,
        blocks,
        matching.stream()
            .map(RunObservationService.Span::nodeId)
            .filter(java.util.Objects::nonNull)
            .distinct()
            .toList());
  }

  /** 6 号门拦截：只有被拒绝、过期或显式否决的审批单才算拦截，等待中不计入。 */
  private static int blocks(List<RunObservationService.Span> spans) {
    return (int)
        spans.stream()
            .filter(span -> "APPROVAL".equals(span.kind()))
            .filter(
                span -> {
                  String status = span.status();
                  if (status == null) return false;
                  String upper = status.toUpperCase(java.util.Locale.ROOT);
                  return upper.contains("DENIED")
                      || upper.contains("DENY")
                      || upper.contains("REJECT")
                      || upper.contains("EXPIRED");
                })
            .count();
  }

  /** 压缩节省率：取本次运行所有模型调用记录里保存比例的平均值。 */
  private Integer compactionSavedPercent(String runId) {
    var values = new ArrayList<Integer>();
    for (var row : payloads.findByRunId(runId)) {
      String raw = row.getContextBreakdownJson();
      if (raw == null || raw.isBlank()) continue;
      try {
        values.add(json.readValue(raw, ContextBreakdown.class).savedPercent());
      } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
        // 历史载荷可能不是当前结构：跳过这一条而不是整条运行报错。
      }
    }
    if (values.isEmpty()) return null;
    return (int) Math.round(values.stream().mapToInt(Integer::intValue).average().orElse(0));
  }

  private static int count(List<RunObservationService.Span> spans, String kind) {
    return (int) spans.stream().filter(span -> kind.equals(span.kind())).count();
  }

  /** Span 类型 → 前端架构目录里的模块 id。 */
  private static String moduleOf(String kind) {
    return switch (kind) {
      case "NODE" -> "run-lifecycle";
      case "ATTEMPT" -> "agent-assembly";
      case "TOOL", "APPROVAL" -> "tool-gateway";
      case "MODEL", "COMPACTION" -> "model-byok";
      case "CHECKPOINT" -> "delivery-monitoring";
      default -> "run-lifecycle";
    };
  }

  /** 跳转锚点：每个 Span 类型落到运行详情里对应的玻璃盒 Tab。 */
  static String glassboxTabOf(String kind) {
    return switch (kind) {
      case "NODE", "CHECKPOINT" -> "result";
      case "ATTEMPT" -> "assembly";
      case "TOOL", "APPROVAL" -> "gateway";
      case "MODEL", "COMPACTION" -> "llm";
      default -> "result";
    };
  }

  /** 运行是否存在走同一条读取边界；供控制器在返回前显式校验。 */
  RunSnapshot require(String runId) {
    return records.runSnapshot(runId);
  }


}
