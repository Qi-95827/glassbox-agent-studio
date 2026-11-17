package dev.workbench.memory;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 本次装配记录；一致性仅检查引用数量和顺序，不证明内容完整或模型已接收。 */
public record MemoryLoad(
    boolean recorded,
    UUID workspaceId,
    Instant loadedAt,
    Integer declaredCount,
    MemoryLoadIntegrity integrity,
    List<MemoryReference> references) {
  public MemoryLoad {
    references = List.copyOf(references);
  }
}
