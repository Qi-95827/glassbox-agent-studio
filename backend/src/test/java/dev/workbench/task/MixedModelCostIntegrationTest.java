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
  @Autowired dev.workbench.persistence.repository.RunCostLimitRepository limits;

  @Test
  void legacyEmptyPriceMappingSameModelForkPreservesParentFrozenPriceVersion() {
    var kind = ModelKind.forMode(ModelMode.DEEPSEEK);
    var v1 = costs.publish("default", kind.code(), "CNY", 2_000_000, 4_000_000, 100, 100, "test-v1");
    // 之后平台对同一模型发布同币种新单价（v2 将成为新的 active 版本，单价远高于 v1）。
    var v2 = costs.publish("default", kind.code(), "CNY", 9_000_000, 9_000_000, 100, 100, "test-v2");

    for (String legacyJson : java.util.Arrays.asList("{}", null)) {
      String parentRunId = UUID.randomUUID().toString();
      String childRunId = UUID.randomUUID().toString();
      // 旧账本没有逐模型映射，仅靠 priceVersionId 冻结 v1 单价。
      limits.save(
          new dev.workbench.persistence.entity.RunCostLimitEntity(
              parentRunId,
              5_000L,
              v1.id(),
              legacyJson,
              "2026-01-01T00:00:00Z",
              "2026-01-01T00:00:00Z"));

      // 同模型分叉（不覆盖模型变量）：必须冻结继承父运行原 v1，绝不能静默切换到当前最新 v2。
      var prepared = costs.prepareInheritedLimit(parentRunId, ModelMode.DEEPSEEK).orElseThrow();
      assertTrue(
          prepared.modelPriceVersionsJson().contains(v1.id()),
          "旧账本同模型分叉必须保留父运行冻结的 v1 价格版本：" + prepared.modelPriceVersionsJson());
      assertFalse(
          prepared.modelPriceVersionsJson().contains(v2.id()),
          "旧账本同模型分叉不得静默切换为当前最新 v2：" + prepared.modelPriceVersionsJson());

      costs.applyPreparedLimit(childRunId, prepared);
      var reservation = costs.reserve(childRunId, kind);
      var settlement = costs.settle(reservation, Integer.valueOf(100), Integer.valueOf(100));
      assertEquals(
          Long.valueOf(600L),
          settlement.actualMicros(),
          "子运行预留与结算必须按父运行冻结的 v1（2M/4M -> 600）计价，而非 v2（9M/9M -> 1800）");
      var reservations = (java.util.List<?>) costs.estimate(childRunId).get("RESERVATIONS");
      var evidence = (java.util.Map<?, ?>) reservations.getFirst();
      assertEquals(2_000_000L, evidence.get("INPUT_MICROS_PER_MILLION"));
    }
  }

  @Test
  void historicalEmptyPriceMappingIsCompletedBeforeInheritance() {
    var targetKind = ModelKind.forMode(ModelMode.DEEPSEEK);
    var mockPrice = costs.publish("default", "mock", "CNY", 0, 0, 100, 100, "test");
    costs.publish("default", targetKind.code(), "CNY", 2_000_000, 4_000_000, 100, 100, "test");
    String parentRunId = UUID.randomUUID().toString();
    String childRunId = UUID.randomUUID().toString();
    // 历史预算行只有默认价格版本，modelPriceVersionsJson 是空映射。
    limits.save(
        new dev.workbench.persistence.entity.RunCostLimitEntity(
            parentRunId,
            5_000L,
            mockPrice.id(),
            "{}",
            "2026-01-01T00:00:00Z",
            "2026-01-01T00:00:00Z"));

    var prepared =
        costs.prepareInheritedLimit(parentRunId, ModelMode.DEEPSEEK).orElseThrow();
    assertEquals(5_000L, prepared.remainingMicros());
    assertTrue(
        prepared.modelPriceVersionsJson().contains(targetKind.code()),
        "历史空映射必须在继承前补全目标模型价格：" + prepared.modelPriceVersionsJson());

    costs.applyPreparedLimit(childRunId, prepared);
    var reservation = costs.reserve(childRunId, targetKind);
    var settlement = costs.settle(reservation, Integer.valueOf(100), Integer.valueOf(100));
    assertEquals(Long.valueOf(600L), settlement.actualMicros());
  }

  @Test
  void preparedLimitUsesTheFrozenPriceEvenIfThePriceTableChangesBeforeApply() {
    var targetKind = ModelKind.forMode(ModelMode.DEEPSEEK);
    var mockPrice = costs.publish("default", "mock", "CNY", 0, 0, 100, 100, "test");
    costs.publish("default", targetKind.code(), "CNY", 2_000_000, 4_000_000, 100, 100, "test");
    String parentRunId = UUID.randomUUID().toString();
    String childRunId = UUID.randomUUID().toString();
    limits.save(
        new dev.workbench.persistence.entity.RunCostLimitEntity(
            parentRunId,
            5_000L,
            mockPrice.id(),
            "{}",
            "2026-01-01T00:00:00Z",
            "2026-01-01T00:00:00Z"));

    var prepared =
        costs.prepareInheritedLimit(parentRunId, ModelMode.DEEPSEEK).orElseThrow();
    // 冻结后平台又发布了新价格版本：落库必须仍使用快照里的旧绑定，不因二次查价而变化。
    costs.publish("default", targetKind.code(), "CNY", 9_000_000, 9_000_000, 100, 100, "test");
    costs.applyPreparedLimit(childRunId, prepared);

    var stored = limits.findById(childRunId).orElseThrow();
    assertEquals(prepared.modelPriceVersionsJson(), stored.getModelPriceVersionsJson());
    assertDoesNotThrow(() -> costs.reserve(childRunId, targetKind));
  }


  @Test
  void hardBudgetCanBeCreatedForOneModeAndSettledWithAnotherModelsPrice() {
    costs.publish("default", "mock", "CNY", 0, 0, 100, 100, "test");
    costs.publish("default", "deepseek-flash", "CNY", 2_000_000, 4_000_000, 100, 100, "test");
    String runId = UUID.randomUUID().toString();
    // Task default mode is mock; binding must not be rejected because a node later uses deepseek.
    costs.bindLimit(runId, costs.requireBudgetPricing(java.util.List.of(ModelKind.MOCK, ModelKind.DEEPSEEK_FLASH), 10_000L));

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
    costs.bindLimit(runId, costs.requireBudgetPricing(java.util.List.of(ModelKind.MOCK, ModelKind.DEEPSEEK_FLASH), 300L));

    costs.reserve(runId, ModelKind.DEEPSEEK_FLASH);
    var error =
        assertThrows(
            dev.workbench.framework.control.StopExecution.class,
            () -> costs.reserve(runId, ModelKind.DEEPSEEK_FLASH));
    assertEquals(dev.workbench.domain.StopCause.COST_LIMIT, error.stopCause());
  }

  @Test
  void crossPlatformForkInheritsRemainingBudgetAndBindsTargetPlatformModelPrice() {
    var targetKind = ModelKind.forMode(ModelMode.DEEPSEEK);
    costs.publish("default", "mock", "CNY", 0, 0, 100, 100, "test");
    costs.publish("default", targetKind.code(), "CNY", 2_000_000, 4_000_000, 100, 100, "test");
    String parentRunId = UUID.randomUUID().toString();
    String childRunId = UUID.randomUUID().toString();
    // 父运行只绑定了 mock 的价格版本，没有 deepseek。
    costs.bindLimit(
        parentRunId, costs.requireBudgetPricing(java.util.List.of(ModelKind.MOCK), 5_000L));

    // 分叉显式切到 DEEPSEEK：前置校验应放行，继承后子运行可正常按目标模型预留结算。
    assertDoesNotThrow(() -> costs.requireForkPricingReady(parentRunId, ModelMode.DEEPSEEK));
    costs.inheritLimit(parentRunId, childRunId, ModelMode.DEEPSEEK);

    var reservation = costs.reserve(childRunId, targetKind);
    var settlement = costs.settle(reservation, Integer.valueOf(100), Integer.valueOf(100));
    assertEquals(Long.valueOf(600L), settlement.actualMicros());
    assertFalse(settlement.unknown());
  }

  @Test
  void crossPlatformForkRejectsMismatchedCurrencyBeforePersisting() {
    var targetKind = ModelKind.forMode(ModelMode.DEEPSEEK);
    costs.publish("default", "mock", "CNY", 0, 0, 100, 100, "test");
    costs.publish("default", targetKind.code(), "USD", 2_000_000, 4_000_000, 100, 100, "test");
    String parentRunId = UUID.randomUUID().toString();
    costs.bindLimit(
        parentRunId, costs.requireBudgetPricing(java.util.List.of(ModelKind.MOCK), 5_000L));

    var error =
        assertThrows(
            IllegalStateException.class,
            () -> costs.requireForkPricingReady(parentRunId, ModelMode.DEEPSEEK));
    assertTrue(error.getMessage().contains("币种"), error.getMessage());
  }
}
