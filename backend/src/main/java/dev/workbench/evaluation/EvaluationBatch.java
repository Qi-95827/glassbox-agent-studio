package dev.workbench.evaluation;

import java.time.Instant;
import java.util.UUID;

/** 评测批次摘要，时间均为 UTC。 */
public record EvaluationBatch(
    UUID id,
    String suite,
    EvaluationStatus status,
    Instant createdAt,
    Instant finishedAt,
    String definitionJson,
    String definitionSha256,
    Integer suiteVersion,
    String configJson,
    String configSha256,
    String requestedBy) {}
