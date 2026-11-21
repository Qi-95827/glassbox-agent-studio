package dev.workbench.planning;

import dev.workbench.domain.ModelKind;
import java.util.List;

/** 确认时冻结的实际模型价格版本；后续发布新价格不影响本次运行。 */
public record BudgetPricing(long maxCostMicros, List<ModelPrice> prices) {
  public BudgetPricing {
    if (maxCostMicros < 1 || maxCostMicros > 1_000_000_000L)
      throw new IllegalArgumentException("费用硬预算须在 1 到 1000000000 微单位之间。");
    prices = List.copyOf(prices);
  }

  public ModelPrice forModel(ModelKind model) {
    return prices.stream()
        .filter(item -> item.model() == model)
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("预算未绑定模型价格：" + model.code()));
  }

  public record ModelPrice(ModelKind model, String priceVersionId, String currency) {
    public ModelPrice {
      if (model == null || priceVersionId == null || priceVersionId.isBlank() || currency == null)
        throw new IllegalArgumentException("模型价格绑定不完整。");
    }
  }
}
