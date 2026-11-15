package dev.workbench.domain;

/** 0.1 支持恢复的安全节点，不代表外部副作用可以回滚。 */
public enum CheckpointStage {
  /** 模拟工具尚未调用，可从此节点重新开始调用。 */
  BEFORE_TOOL,
  /** 分析完成且安全条件满足，恢复时仅重新执行审查。 */
  BEFORE_REVIEW,
  /** 模拟工具结果已经确认，可复用该结果。 */
  AFTER_TOOL
}
