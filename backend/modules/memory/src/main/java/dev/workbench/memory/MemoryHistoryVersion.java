package dev.workbench.memory;

import java.time.Instant;
import java.util.UUID;

/** 用户确认记忆某一历史版本，包含当时的正文。 */
public record MemoryHistoryVersion(
    UUID memoryId,
    int version,
    MemoryCategory category,
    String content,
    MemoryStatus status,
    Instant changedAt) {}
