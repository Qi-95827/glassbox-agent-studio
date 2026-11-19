package dev.workbench.app;

import dev.workbench.task.CostService;
import java.util.List;
import org.springframework.web.bind.annotation.*;

/** 费用价格版本、估算和硬预算预留查询。 */
@RestController
@RequestMapping("/api/cost")
public class CostController {
  private final CostService costs;

  public CostController(CostService costs) {
    this.costs = costs;
  }

  public record PriceRequest(
      String provider,
      String model,
      String currency,
      Long inputMicrosPerMillion,
      Long outputMicrosPerMillion,
      Integer inputTokenUpperBound,
      Integer outputTokenUpperBound,
      String publishedBy) {}

  @GetMapping("/prices")
  List<CostService.Price> prices() {
    return costs.listPrices();
  }

  @PostMapping("/prices")
  Object publish(@RequestBody PriceRequest request) {
    if (request.inputMicrosPerMillion() == null
        || request.outputMicrosPerMillion() == null
        || request.inputTokenUpperBound() == null
        || request.outputTokenUpperBound() == null)
      throw new IllegalArgumentException("价格和单次 Token 上界必须完整填写。");
    return costs.publish(
        request.provider(),
        request.model(),
        request.currency(),
        request.inputMicrosPerMillion(),
        request.outputMicrosPerMillion(),
        request.inputTokenUpperBound(),
        request.outputTokenUpperBound(),
        request.publishedBy());
  }

  @GetMapping("/estimate")
  Object estimate(@RequestParam String runId) {
    return costs.estimate(runId);
  }
}
