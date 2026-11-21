package dev.workbench.memory;

import java.util.UUID;
import dev.workbench.domain.MemoryCategory;

/** 一次装配中选中的记忆版本；不包含记忆正文。 */
public record MemoryReference(int sequence, UUID memoryId, int version, MemoryCategory category) {}
