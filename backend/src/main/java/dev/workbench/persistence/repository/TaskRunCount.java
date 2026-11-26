package dev.workbench.persistence.repository;

/** 任务下的运行总数；任务列表用它提示「已迭代 N 轮」而不必逐行再查。 */
public record TaskRunCount(String taskId, long runCount) {}
