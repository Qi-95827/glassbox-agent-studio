package dev.workbench.framework.context;

/** 四级上下文压缩等级；L0 只观测，L1/L2/L3 逐级触发。 */
public enum CompactionTier {
  L0_OBSERVE_ONLY,
  L1_TOOL_OFFLOAD,
  L2_SLIDING_SUMMARY,
  L3_HARD_GUARD
}
