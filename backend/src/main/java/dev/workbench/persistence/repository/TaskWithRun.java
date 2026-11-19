package dev.workbench.persistence.repository;

/** 任务列表行：任务详情加最新运行和待确认计划状态。 */
public record TaskWithRun(
    TaskData task, String runId, String status, Boolean planReviewPending) {}