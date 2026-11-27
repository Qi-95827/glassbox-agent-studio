package dev.workbench.planning;

/**
 * 费用上限继承端口。
 *
 * <p>{@code planning} 需要把源运行的预算沿用给分叉/恢复出的新运行，但直接依赖 {@code task.CostService}
 * 会与既有的 {@code task → planning} 方向形成包循环。这里用一个只含单个方法的中立端口反转依赖：
 * 实现仍由 {@code task.CostService} 提供，Spring 按类型注入。
 */
public interface CostLimitPort {
  /** 把父运行剩余的费用上限继承给子运行；无父预算时实现应为无操作。 */
  void inheritLimit(String parentRunId, String childRunId);
}
