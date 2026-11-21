package dev.workbench.task;

import static org.junit.jupiter.api.Assertions.*;

import dev.workbench.app.WorkbenchApplication;
import dev.workbench.domain.ModelKind;
import dev.workbench.domain.ModelMode;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** Mixed-model hard budget: each ModelCall is priced by its actual model. */
@SpringBootTest(
    classes = WorkbenchApplication.class,
    properties = {
      "spring.datasource.url=jdbc:h2:mem:mixedmodelcost;DB_CLOSE_DELAY=-1",
      "workbench.workspace-root=${java.io.tmpdir}/agent-workbench-mixed-model-cost"
    })
class MixedModelCostIntegrationTest {
  @Autowired CostService costs;

  @Test
  void hardBudgetCanBeCreatedForOneModeAndSettledWithAnotherModelsPrice() {
    costs.publish("default", "mock", "CNY", 0, 0, 100, 100, "test");
    costs.publish("default", "deepseek-flash", "CNY", 2_000_000, 4_000_000, 100, 100, "test");
    String runId = UUID.randomUUID().toString();
    // Task default mode is mock; binding must not be rejected because a node later uses deepseek.
    costs.bindLimit(runId, ModelMode.MOCK, 10_000L);

    var reservation = costs.reserve(runId, ModelKind.DEEPSEEK_FLASH);
    var settlement = costs.settle(reservation, Integer.valueOf(100), Integer.valueOf(100));

    assertEquals(Long.valueOf(600L), settlement.actualMicros());
    assertEquals(600L, settlement.settledMicros());
    assertEquals(0L, settlement.overBudgetMicros());
    assertFalse(settlement.unknown());
    var reservations = (java.util.List<?>) costs.estimate(runId).get("RESERVATIONS");
    var evidence = (java.util.Map<?, ?>) reservations.getFirst();
    assertEquals("deepseek-flash", evidence.get("PRICE_MODEL"));
    assertEquals("CNY", evidence.get("CURRENCY"));
    assertEquals(2_000_000L, evidence.get("INPUT_MICROS_PER_MILLION"));
  }

  @Test
  void mixedCurrencyOrMissingPriceIsRejectedBeforeCreatingARun() {
    costs.publish("default", "mock", "CNY", 0, 0, 100, 100, "test");
    costs.publish("default", "deepseek-flash", "USD", 1_000_000, 1_000_000, 100, 100, "test");
    var mixedCurrency =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                costs.requireBudgetPricing(
                    java.util.List.of(ModelKind.MOCK, ModelKind.DEEPSEEK_FLASH), 10_000L));
    assertTrue(mixedCurrency.getMessage().contains("币种"));
  }

  @Test
  void mixedModelBudgetStillBlocksWhenActualCallsExceedTheLimit() {
    costs.publish("default", "mock", "CNY", 0, 0, 100, 100, "test");
    costs.publish("default", "deepseek-flash", "CNY", 1_000_000, 1_000_000, 100, 100, "test");
    String runId = UUID.randomUUID().toString();
    costs.bindLimit(runId, ModelMode.MOCK, 300L);

    costs.reserve(runId, ModelKind.DEEPSEEK_FLASH);
    var error =
        assertThrows(
            dev.workbench.framework.control.StopExecution.class,
            () -> costs.reserve(runId, ModelKind.DEEPSEEK_FLASH));
    assertEquals(dev.workbench.domain.StopCause.COST_LIMIT, error.stopCause());
  }
}
