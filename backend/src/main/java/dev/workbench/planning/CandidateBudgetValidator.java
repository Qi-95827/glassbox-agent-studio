package dev.workbench.planning;

import dev.workbench.domain.ModelKind;
import java.util.Collection;

/** 确认前核对实际模型集合的价格与币种，并返回冻结的价格版本。 */
public interface CandidateBudgetValidator {
  BudgetPricing requireBudgetPricing(Collection<ModelKind> models, long maxCostMicros);
}
