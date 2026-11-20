package dev.workbench.domain;

import java.util.UUID;

/** 从持久化层读取的运行状态；耗时单位为毫秒。 */
public record RunSnapshot(
    UUID id,
    UUID taskId,
    UUID parentId,
    RunStatus status,
    int rounds,
    int tools,
    Integer maxRounds,
    Integer maxTools,
    Integer maxPerTool,
    Long maxReportedTokens,
    Integer maxIdenticalTools,
    Integer maxSeconds,
    long elapsedMs,
    Integer maxAgentRounds,
    Integer maxAgentTools) {}
