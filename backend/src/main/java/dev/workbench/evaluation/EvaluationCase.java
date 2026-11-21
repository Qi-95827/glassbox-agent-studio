package dev.workbench.evaluation;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import dev.workbench.domain.CaseStatus;

/** 单个样本的可追溯结果；未启动时 runId 为空。 */
public record EvaluationCase(
    UUID id,
    SeedCase code,
    String title,
    CaseStatus status,
    UUID runId,
    String explanation,
    Instant updatedAt,
    List<EvaluationCheck> checks) {}
