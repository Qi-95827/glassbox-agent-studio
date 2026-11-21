package dev.workbench.persistence.repository.view;

public record CheckInputView(
    String checkId,
    String dependencyId,
    String kind,
    String sourceAttempt,
    String sourceCheck,
    String sourceRun,
    String sha256,
    String agentResult,
    String checkSummary) {}
