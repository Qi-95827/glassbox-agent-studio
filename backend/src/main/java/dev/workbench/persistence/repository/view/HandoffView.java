package dev.workbench.persistence.repository.view;

public record HandoffView(
    Integer schemaVersion,
    String acceptedAt,
    String senderAttempt,
    String senderNode,
    String senderAgent,
    String dependencyId,
    String receiverAttempt,
    String receiverNode,
    String receiverAgent,
    String sourceRunId,
    String sourceResult) {}
