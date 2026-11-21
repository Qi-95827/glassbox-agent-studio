package dev.workbench.planning;

import dev.workbench.domain.ModelKind;
import java.util.Collection;

/** 确认前核对实际模型集合的价格与币种；由费用服务实现。 */
public interface CandidateBudgetValidator {
  void requireBudgetPricing(Collection<ModelKind> models, long maxCostMicros);
}
