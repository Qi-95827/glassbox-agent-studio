package dev.workbench.persistence.repository.view;

import dev.workbench.framework.workflow.check.SystemCheckStatus;

public record CheckSettlement(String nodeId, SystemCheckStatus status, String label) {}
