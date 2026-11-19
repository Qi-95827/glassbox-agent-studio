package dev.workbench.persistence.repository;

import dev.workbench.persistence.entity.WorkflowCheckpointEntity;

/** 检查点的只读投影。构造参数使用映射属性名，供派生查询选择返回字段。 */
public record WorkflowCheckpointData(
    String id,
    Integer version,
    Integer completedNodes,
    Integer rounds,
    Integer toolCalls,
    Long elapsedMs,
    String boundarySha256,
    String createdAt) {

  public int actualToolCalls() {
    return WorkflowCheckpointEntity.resolveToolCalls(toolCalls);
  }
}