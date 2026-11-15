package dev.workbench.evaluation;

import java.util.UUID;

/** 案例关联运行的资源快照；不可用或没有已知值时相应字段为空。 */
public record CaseResources(
    UUID runId,
    boolean available,
    Long recordedDurationMs,
    Long modelCalls,
    Long toolCalls,
    Long knownInputTokens,
    Long unknownInputCalls,
    Long knownOutputTokens,
    Long unknownOutputCalls) {}
