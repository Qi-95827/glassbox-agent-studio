package dev.workbench.framework.memory;

import java.util.List;

/**
 * 一次混合召回结果；得分与理由必须写入装配快照，供玻璃盒解释“为什么召回”。
 */
public record MemoryMatch(
    String memoryId,
    MemoryType memoryType,
    double totalScore,
    String matchedReason,
    String content,
    int memoryVersion) {
  public MemoryMatch {
    if (memoryId == null || memoryId.isBlank()) throw new IllegalArgumentException("记忆标识不能为空。");
    memoryType = memoryType == null ? MemoryType.SEMANTIC : memoryType;
    totalScore = Math.max(0, Math.min(1, totalScore));
    matchedReason = matchedReason == null ? "" : matchedReason;
    content = content == null ? "" : content;
    if (memoryVersion < 1) throw new IllegalArgumentException("记忆版本无效。");
  }

  public MemoryMatch(
      String memoryId,
      MemoryType memoryType,
      double totalScore,
      String matchedReason,
      String content) {
    this(memoryId, memoryType, totalScore, matchedReason, content, 1);
  }
}
