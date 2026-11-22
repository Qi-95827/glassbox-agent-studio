package dev.workbench.memory;

import java.util.UUID;
import dev.workbench.domain.MemoryCategory;
import dev.workbench.framework.memory.MemoryType;

/** 一次装配中选中的记忆版本、混合得分与召回理由；不包含记忆正文。 */
public record MemoryReference(
    int sequence,
    UUID memoryId,
    int version,
    MemoryCategory category,
    MemoryType memoryType,
    double score,
    String matchedReason) {
  public MemoryReference {
    memoryType = memoryType == null ? MemoryType.SEMANTIC : memoryType;
    score = Math.max(0, Math.min(1, score));
    matchedReason = matchedReason == null ? "" : matchedReason;
  }

  /** 兼容历史引用记录。 */
  public MemoryReference(int sequence, UUID memoryId, int version, MemoryCategory category) {
    this(sequence, memoryId, version, category, MemoryType.SEMANTIC, 0, "");
  }
}
