package dev.workbench.orchestration.events;

import dev.workbench.domain.EventType;
import java.time.Instant;
import java.util.UUID;

/** 数据库实际发生的运行事件；序号为全局递增 ID，不保证每个运行连续。 */
public record RunEvent(long id, UUID runId, EventType type, String message, Instant occurredAt) {}
