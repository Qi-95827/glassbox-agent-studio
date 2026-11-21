package dev.workbench.persistence.repository.view;

import dev.workbench.domain.InvocationStatus;
import dev.workbench.domain.StopCause;

public record AttemptView(
    String id,
    String nodeId,
    Integer number,
    String agentId,
    InvocationStatus status,
    String createdAt,
    String updatedAt,
    StopCause cause) {}
