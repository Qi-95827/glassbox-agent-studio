package dev.workbench.framework.event;

import dev.workbench.domain.EventType;
import java.time.Instant;
import java.util.UUID;

/**
 * 数据库实际发生的运行事件；序号为全局递增 ID，不保证每个运行连续。
 *
 * <p>结构化引用均可空。旧事件没有写入关联时为 {@code null}，读取方不得据消息文本补造节点归属。
 */
public record RunEvent(
    long id,
    UUID runId,
    EventType type,
    String message,
    Instant occurredAt,
    UUID planVersionId,
    UUID nodeId,
    UUID attemptId,
    UUID agentId,
    UUID checkId,
    UUID modelCallId,
    UUID toolCallId,
    String reasonCode) {}
