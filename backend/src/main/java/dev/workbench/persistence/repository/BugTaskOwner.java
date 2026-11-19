package dev.workbench.persistence.repository;

/** 运行归属的模拟 Bug 和工作区；用于只读取证据。 */
public record BugTaskOwner(String bugId, String workspaceId) {}
