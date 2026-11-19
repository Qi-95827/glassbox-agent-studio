package dev.workbench.persistence.repository;

/** 交付视图需要的运行终态摘要。 */
public record BugRunSummary(String status, String reason, String stopCause) {}
