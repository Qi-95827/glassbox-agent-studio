package dev.workbench.agent;

/** 会话持久化的观测状态；不代表具备恢复能力。 */
public enum AgentSessionStatus {
  SAVED,
  NOT_SAVED,
  UNAVAILABLE
}
