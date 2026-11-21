package dev.workbench.persistence.repository.view;

import dev.workbench.framework.workflow.check.SystemCheckStatus;

public record CheckResult(
    String dependencyId,
    String key,
    String label,
    Integer sequence,
    String runId,
    String checkId,
    SystemCheckStatus status,
    String summary,
    String evidence) {}
