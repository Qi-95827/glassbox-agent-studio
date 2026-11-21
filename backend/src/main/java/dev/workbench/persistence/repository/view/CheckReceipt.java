package dev.workbench.persistence.repository.view;

public record CheckReceipt(
    String checkId,
    String dependencyNodeId,
    String dependencyNodeLabel,
    String receiverNodeId,
    String receiverNodeLabel,
    String receiverAttemptId,
    String acceptedAt,
    String summary,
    String evidence) {}
