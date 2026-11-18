package dev.workbench.framework.workflow.check;

/** 一次系统检查尝试的状态；只有 {@link #PASSED} 表示关卡通过。 */
public enum SystemCheckStatus {
  /** 已认领，检查尚未结算。 */
  RUNNING,
  /** 检查条件成立，下游可以继续。 */
  PASSED,
  /** 检查条件不成立，必需下游不得启动。 */
  FAILED,
  /** 缺少判定所需的前置结果或证据，不能视为通过。 */
  BLOCKED,
  /** 进程中断，检查结果未知。 */
  INTERRUPTED;

  /** 该状态是否允许下游继续。 */
  public boolean passed() {
    return this == PASSED;
  }
}
