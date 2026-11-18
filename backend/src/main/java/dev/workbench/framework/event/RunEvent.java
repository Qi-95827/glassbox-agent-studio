package dev.workbench.framework.event;

import dev.workbench.domain.EventType;
import java.time.Instant;
import java.util.UUID;

/**
 * 数据库实际发生的运行事件。
 *
 * <p>{@code id} 是全局自增 ID，仅供对账，不作为游标；{@code runSeq} 是该运行内的提交序号，读取方用它
 * 增量续读。结构化引用均可空。旧事件没有写入关联时为 {@code null}，读取方不得据消息文本补造节点归属。
 */
public record RunEvent(
    long id,
    long runSeq,
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
