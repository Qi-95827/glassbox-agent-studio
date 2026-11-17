package dev.workbench.evaluation;

/** 仅对相同套件的明确通过／失败结果作状态对照。 */
public enum ComparisonOutcome {
  UNCHANGED_PASS,
  UNCHANGED_FAIL,
  IMPROVED,
  REGRESSED,
  INCONCLUSIVE
}
