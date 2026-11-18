package dev.workbench.framework.workflow.plan;

import dev.workbench.domain.InvocationStatus;
import dev.workbench.domain.StopCause;
import java.util.UUID;

/** 状态与时间来自这次尝试对应的实际 Agent 调用。 */
public record WorkflowAttempt(
    UUID id,
    UUID nodeId,
    int number,
    UUID agentId,
    InvocationStatus status,
    java.time.Instant startedAt,
    java.time.Instant updatedAt,
    StopCause stopCause) {}
