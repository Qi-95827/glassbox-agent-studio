package dev.workbench.task;

import static org.junit.jupiter.api.Assertions.*;

import dev.workbench.app.WorkbenchApplication;
import dev.workbench.domain.ModelKind;
import dev.workbench.domain.ModelMode;
import dev.workbench.domain.StopCause;
import dev.workbench.framework.control.StopExecution;
import dev.workbench.orchestration.CreateTaskRequest;
import dev.workbench.orchestration.RunService;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import dev.workbench.store.RunRecordService;

/** 费用估算与硬预算：可计价上界预留、并发预留、未知用量和停止原因。 */
@SpringBootTest(
    classes = WorkbenchApplication.class,
    properties = {
      "spring.datasource.url=jdbc:h2:mem:task6cost;DB_CLOSE_DELAY=-1",
      "workbench.workspace-root=${java.io.tmpdir}/agent-workbench-task6-cost"
    })
class CostBudgetIntegrationTest {
  @Autowired CostService costs;
  @Autowired RunService runs;
  @Autowired RunRecordService records;
  @Autowired org.springframework.transaction.support.TransactionTemplate tx;

  @Test
  void concurrentReservationsCannotExceedHardBudget() throws Exception {
    costs.publish("default", "mock", "CNY", 1_000_000, 1_000_000, 100, 100, "test");
    String run = java.util.UUID.randomUUID().toString();
    costs.bindLimit(run, ModelMode.MOCK, 300L);
    var latch = new CountDownLatch(1);
    var successes = new AtomicInteger();
    var executor = Executors.newFixedThreadPool(2);
    for (int i = 0; i < 2; i++) {
      executor.submit(
          () -> {
            try {
              latch.await();
              costs.reserve(run, ModelKind.MOCK);
              successes.incrementAndGet();
            } catch (StopExecution expected) {
              assertEquals(StopCause.COST_LIMIT, expected.stopCause());
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          });
    }
    latch.countDown();
    executor.shutdown();
    assertTrue(executor.awaitTermination(20, TimeUnit.SECONDS));
    assertEquals(1, successes.get(), "同一运行并发预留只能有一个成功");
  }

  @Test
  void overageIsPersistedBeforeNextReservationIsRejected() {
    costs.publish("default", "mock", "CNY", 1_000_000, 1_000_000, 100, 100, "test");
    String run = java.util.UUID.randomUUID().toString();
    costs.bindLimit(run, ModelMode.MOCK, 300L);
    var reservation = costs.reserve(run, ModelKind.MOCK);
    var settlement = costs.settle(reservation, 100, 400);
    assertEquals(200L, settlement.overBudgetMicros());
    assertEquals(500L, settlement.settledMicros());
    var estimate = costs.estimate(run);
    assertEquals(500L, estimate.get("SETTLED_MICROS"));
    assertEquals(200L, estimate.get("OVERAGE_MICROS"));
    assertEquals(true, estimate.get("OVER_BUDGET"));
    assertThrows(StopExecution.class, () -> costs.reserve(run, ModelKind.MOCK));
  }

  @Test
  void settlementSurvivesOuterTransactionRollbackAndStopsNextCall() {
    costs.publish("default", "mock", "CNY", 1_000_000, 1_000_000, 100, 100, "test");
    String run = java.util.UUID.randomUUID().toString();
    costs.bindLimit(run, ModelMode.MOCK, 300L);
    var reservation = costs.reserve(run, ModelKind.MOCK);
    tx.executeWithoutResult(
        status -> {
          costs.settle(reservation, 100, 400);
          status.setRollbackOnly();
        });
    var estimate = costs.estimate(run);
    assertEquals(500L, estimate.get("SETTLED_MICROS"));
    assertEquals(200L, estimate.get("OVERAGE_MICROS"));
    assertThrows(StopExecution.class, () -> costs.reserve(run, ModelKind.MOCK));
  }

  @Test
  void unknownUsageKeepsReservationAndIsVisibleInEstimate() {
    costs.publish("default", "mock", "CNY", 1_000_000, 1_000_000, 100, 100, "test");
    String run = java.util.UUID.randomUUID().toString();
    costs.bindLimit(run, ModelMode.MOCK, 1_000L);
    var reservation = costs.reserve(run, ModelKind.MOCK);
    costs.settle(reservation, null, null);
    var estimate = costs.estimate(run);
    assertEquals(1, estimate.get("UNKNOWN_CALLS"));
    assertEquals(200L, estimate.get("RESERVED_MICROS"));
    assertEquals(0L, estimate.get("SETTLED_MICROS"));
  }

  @Test
  void taskWithInsufficientCostBudgetStopsBeforeModelCall() {
    costs.publish("default", "mock", "CNY", 1_000_000, 1_000_000, 4096, 4096, "test");
    String run =
        runs.create(
            new CreateTaskRequest(
                "费用硬预算验证",
                "normal",
                2,
                1,
                30,
                "mock",
                null,
                null,
                false,
                null,
                false,
                null,
                java.util.List.of(),
                java.util.List.of(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                false,
                null,
                null,
                null,
                100L));
    waitTerminal(run);
    assertEquals("LIMITED", records.runSnapshot(run).status().name());
    assertEquals(StopCause.COST_LIMIT.name(), records.run(run).get("STOP_CAUSE"));
    var estimate = costs.estimate(run);
    assertNotNull(estimate);
    assertEquals(100L, estimate.get("MAX_COST_MICROS"));
  }

  private void waitTerminal(String runId) {
    long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
    while (records.runSnapshot(runId).status().canCancel()) {
      if (System.nanoTime() > deadline) fail("运行未结束：" + runId);
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        fail("等待被中断");
      }
    }
  }
}
