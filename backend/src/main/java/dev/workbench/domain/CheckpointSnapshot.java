package dev.workbench.domain;

import java.util.UUID;

/** 恢复时使用的检查点数据；完整性校验在读取边界完成。 */
public record CheckpointSnapshot(
    UUID id,
    UUID runId,
    CheckpointStage stage,
    int rounds,
    int tools,
    long elapsedMs,
    String toolResult,
    Integer version) {
  /** 兼容不需要检查版本的内部测试调用。 */
  public CheckpointSnapshot(
      UUID id,
      UUID runId,
      CheckpointStage stage,
      int rounds,
      int tools,
      long elapsedMs,
      String toolResult) {
    this(id, runId, stage, rounds, tools, elapsedMs, toolResult, null);
  }
}
