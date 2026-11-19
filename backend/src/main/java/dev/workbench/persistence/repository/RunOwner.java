package dev.workbench.persistence.repository;

/** 运行归属投影，用于权限和恢复链校验。 */
public record RunOwner(String taskId, String parentId) {}