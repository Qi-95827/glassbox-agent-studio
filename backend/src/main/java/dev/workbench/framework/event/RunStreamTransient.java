package dev.workbench.framework.event;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 不入库的瞬态 SSE 片段：模型增量与安检门实时状态。
 *
 * <p>这类帧只服务「运行当下」的观感，因此刻意不落库：一次长回答会产生成百上千个 delta，
 * 写进事件表既撑爆游标分页，也违背「事件表只记录可追溯事实」的原则。终态事实仍由
 * {@code ToolCallTrace} 与模型调用快照持久化，瞬态帧丢失不影响任何事后审计。
 *
 * @param runId 运行标识，用于路由到对应订阅者。
 * @param kind 帧类型：{@code MODEL_DELTA}、{@code MODEL_REASONING_DELTA}、{@code TOOL_GATE_PROGRESS}。
 * @param text 正文片段或工具名；始终非空字符串，避免前端判空分支。
 * @param nodeId 产生该片段的节点；未知时为空。
 * @param detail 结构化附加信息（例如单道安检门的判定）；无附加信息时为空 Map。
 * @param occurredAt 服务端产生时刻。
 */
public record RunStreamTransient(
    UUID runId, String kind, String text, String nodeId, Map<String, Object> detail, Instant occurredAt) {
  public RunStreamTransient {
    kind = kind == null || kind.isBlank() ? "MODEL_DELTA" : kind;
    text = text == null ? "" : text;
    detail = detail == null ? Map.of() : Map.copyOf(detail);
    occurredAt = occurredAt == null ? Instant.now() : occurredAt;
  }

  /** 兼容只关心运行、类型与正文的调用方（例如测试与旧埋点）。 */
  public RunStreamTransient(UUID runId, String kind, String text, Instant occurredAt) {
    this(runId, kind, text, null, Map.of(), occurredAt);
  }
}
