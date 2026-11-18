package dev.workbench.framework.workflow.plan;

import java.time.Instant;
import java.util.UUID;

/** 工作台可查询的动态安全边界；恢复申请仍要复验完整来源。 */
public record WorkflowCheckpoint(
    UUID id,
    int version,
    int completedNodes,
    int rounds,
    long elapsedMs,
    Instant createdAt,
    boolean available) {}
