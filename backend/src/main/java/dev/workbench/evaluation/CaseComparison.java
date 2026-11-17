package dev.workbench.evaluation;

/** 案例按编码匹配；缺少一侧记录时该侧为空。 */
public record CaseComparison(
    SeedCase code,
    String title,
    EvaluationCase baseline,
    EvaluationCase candidate,
    ComparisonOutcome outcome,
    CaseResources baselineResources,
    CaseResources candidateResources) {}
