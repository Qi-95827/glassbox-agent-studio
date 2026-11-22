package dev.workbench.memory;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import dev.workbench.domain.MemoryCategory;
import dev.workbench.domain.MemorySource;
import dev.workbench.domain.MemoryStatus;
import dev.workbench.framework.memory.MemoryType;

/** 工作区范围的记忆；内容始终为不可信背景，候选必须经用户采纳才能注入。 */
public record MemoryRecord(
    UUID id,
    UUID workspaceId,
    MemoryCategory category,
    String content,
    MemorySource source,
    MemoryStatus status,
    int version,
    Instant createdAt,
    Instant updatedAt,
    MemoryType memoryType,
    UUID sourceRunId,
    List<String> pathPatterns,
    double confidence,
    int hitCount,
    Instant lastHitAt) {
  public MemoryRecord {
    memoryType = memoryType == null ? MemoryType.SEMANTIC : memoryType;
    pathPatterns = pathPatterns == null ? List.of() : List.copyOf(pathPatterns);
    confidence = Math.max(0, Math.min(1, confidence));
    hitCount = Math.max(0, hitCount);
  }

  /** 兼容历史 API 与旧测试的九字段构造。 */
  public MemoryRecord(
      UUID id,
      UUID workspaceId,
      MemoryCategory category,
      String content,
      MemorySource source,
      MemoryStatus status,
      int version,
      Instant createdAt,
      Instant updatedAt) {
    this(
        id,
        workspaceId,
        category,
        content,
        source,
        status,
        version,
        createdAt,
        updatedAt,
        MemoryType.SEMANTIC,
        null,
        List.of(),
        1.0,
        0,
        null);
  }
}
