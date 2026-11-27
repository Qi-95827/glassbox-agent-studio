package dev.workbench.planning;

import dev.workbench.domain.ModelMode;

/**
 * 费用上限继承端口。
 *
 * <p>{@code planning} 需要把源运行的预算沿用给分叉/恢复出的新运行，但直接依赖 {@code task.CostService}
 * 会与既有的 {@code task → planning} 方向形成包循环。这里用小粒度端口反转依赖：
 * 实现仍由 {@code task.CostService} 提供，Spring 按类型注入。
 */
public interface CostLimitPort {
  /** 把父运行剩余的费用上限继承给子运行；无父预算时实现应为无操作。 */
  void inheritLimit(String parentRunId, String childRunId);

  /**
   * 校验分叉目标平台模型在父运行预算下的价格版本就绪性。
   *
   * <p>父运行有费用硬预算且子运行显式切到父运行未绑定的新平台模型时，若平台当前没有该模型的生效价格，
   * 必须在分叉落库前同步拒绝，避免派发出首次预留即受控停止的子运行。
   */
  default void requireForkPricingReady(String parentRunId, ModelMode childMode) {}

  /** 把父运行剩余费用上限继承给子运行，并在跨平台模型切换时按生效价格补全子运行的价格绑定。 */
  default void inheritLimit(String parentRunId, String childRunId, ModelMode childMode) {
    inheritLimit(parentRunId, childRunId);
  }
}
