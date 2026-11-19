package dev.workbench.persistence.repository;

/** 任务详情投影，字段名与既有 API 和数据列保持一致。 */
public record TaskData(
    String id,
    String goal,
    String scenario,
    String createdAt,
    String archivedAt,
    String modelMode,
    String workspaceId,
    String fileScope,
    Boolean writeEnabled,
    String bugId,
    Boolean memoryEnabled,
    String constraintsJson,
    String acceptanceJson,
    String entryKind,
    String fileScopesJson,
    Integer version) {}