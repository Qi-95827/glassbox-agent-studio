package dev.workbench.domain;

/** 记忆生命周期：候选必须经用户采纳才能注入任务。 */
public enum MemoryStatus {
  CANDIDATE,
  ACTIVE,
  ARCHIVED,
  DELETED
}
