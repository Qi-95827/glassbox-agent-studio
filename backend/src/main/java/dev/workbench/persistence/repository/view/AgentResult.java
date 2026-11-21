package dev.workbench.persistence.repository.view;

public record AgentResult(
    String dependencyId,
    String key,
    String label,
    Integer sequence,
    String sourceNode,
    String sourceRun,
    String attemptId,
    String agentId,
    String result,
    Boolean workspaceRead,
    Boolean workspaceWrite) {}
