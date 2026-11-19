package dev.workbench.persistence.repository;

/** 工具预算投影。 */
public record ToolBudgetRow(String toolKind, Integer version, Integer maxCalls) {}