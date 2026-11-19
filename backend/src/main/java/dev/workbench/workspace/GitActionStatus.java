package dev.workbench.workspace;

/** Git 与数据库无法共同提交；未知状态要求人工核对，不能自动重试。 */
public enum GitActionStatus {
  STARTED,
  CONFIRMED,
  CONFLICT,
  UNKNOWN,
  REJECTED
}
