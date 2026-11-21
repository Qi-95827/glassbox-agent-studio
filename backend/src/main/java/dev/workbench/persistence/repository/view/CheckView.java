package dev.workbench.persistence.repository.view;

import dev.workbench.framework.workflow.check.SystemCheckStatus;

public record CheckView(
    String id,
    String nodeId,
    String key,
    Integer attemptNo,
    String referenceId,
    Integer referenceVersion,
    SystemCheckStatus status,
    String summary,
    String evidence,
    String externalId,
    String inputsSha256,
    String startedAt,
    String finishedAt,
    String workspaceFingerprint) {}
