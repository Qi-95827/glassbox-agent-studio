package dev.workbench.framework.context;

/**
 * 与具体 Bug 模块解耦的初始检查证据快照。
 *
 * <p>只携带装配上下文所需的只读字段；归属和状态校验由消费方完成。
 */
public record InitialCheck(
    String id,
    String runId,
    String bugId,
    String workspaceId,
    String kind,
    String status,
    String workspaceFingerprint,
    String output) {}
