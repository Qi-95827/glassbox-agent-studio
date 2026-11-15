package dev.workbench.domain;

/** 委派与结果交接是两条独立记录。 */
public enum HandoffDirection {
  DELEGATION,
  RESULT,
  AGENT_TRANSFER
}
