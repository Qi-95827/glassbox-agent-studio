package dev.workbench.domain;

/** 未生成分析后恢复检查点的安全原因；说明中不包含工作区敏感信息。 */
public enum CheckpointSkipReason {
  BUG_TASK("Bug 任务暂不支持分析后恢复，本次未生成分析后检查点。"),
  WRITE_TASK("写入任务暂不支持分析后恢复，本次未生成分析后检查点。"),
  MEMORY_CONTEXT("启用项目背景记忆的工作区任务暂不支持分析后恢复。"),
  WORKSPACE_BEFORE_UNAVAILABLE("分析前无法可靠确认工作区状态，本次未生成分析后检查点。"),
  WORKSPACE_AFTER_UNAVAILABLE("分析后无法可靠确认工作区状态，本次未生成分析后检查点。"),
  WORKSPACE_CHANGED("分析前后的工作区状态不一致，本次未生成分析后检查点。");

  private final String message;

  CheckpointSkipReason(String message) {
    this.message = message;
  }

  /** 返回可公开展示的固定说明。 */
  public String message() {
    return message;
  }
}
