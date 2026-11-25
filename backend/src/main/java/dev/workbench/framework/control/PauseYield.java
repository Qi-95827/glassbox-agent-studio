package dev.workbench.framework.control;

/**
 * 断点/暂停时让出调度线程的信号。
 *
 * <p>暂停不应长期占用固定大小的执行线程池：调度器在**未认领任何节点**的安全边界收到暂停请求时， 先落库 {@code PAUSED}
 * 与断点事件，再抛出本信号退出调度循环，把线程还给执行器；用户点「继续」后由 恢复路径重新派发，继续认领剩余 {@code PENDING} 节点。
 *
 * <p>本信号不是终止原因：运行保持 {@code PAUSED} 状态，绝不被结算为失败或完成。
 */
public final class PauseYield extends RuntimeException {
  private final String runId;

  public PauseYield(String runId) {
    super("运行已暂停，调度线程让出执行器。");
    this.runId = runId;
  }

  public String runId() {
    return runId;
  }
}
