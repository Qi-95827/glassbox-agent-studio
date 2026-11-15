package dev.workbench.memory;

import java.time.Instant;
import java.util.UUID;

/** 工作区范围的用户确认记忆；内容仍为不可信背景。 */
public record MemoryRecord(
    UUID id,
    UUID workspaceId,
    MemoryCategory category,
    String content,
    MemorySource source,
    MemoryStatus status,
    int version,
    Instant createdAt,
    Instant updatedAt) {}
