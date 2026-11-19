package dev.workbench.persistence.repository;

/** 启动时需要中断的运行及其计划版本。 */
public record StartupRun(String id, String planId) {}