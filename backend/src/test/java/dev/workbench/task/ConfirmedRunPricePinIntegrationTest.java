package dev.workbench.task;

import static org.junit.jupiter.api.Assertions.*;

import dev.workbench.app.WorkbenchApplication;
import dev.workbench.domain.ModelKind;
import dev.workbench.domain.ModelMode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** 已确认运行固定价格版本；新价格发布只影响之后确认的运行。 */
@SpringBootTest(
    classes = WorkbenchApplication.class,
    properties = {
      "spring.datasource.url=jdbc:h2:mem:confirmedpricepin;DB_CLOSE_DELAY=-1",
      "workbench.workspace-root=${java.io.tmpdir}/agent-workbench-confirmed-price-pin"
    })
class ConfirmedRunPricePinIntegrationTest {
  @Autowired CostService costs;

  @Test
  void publishingANewPriceDoesNotChangeAnAlreadyConfirmedRunOrItsRecovery() {
    var v1 = costs.publish("default", "mock", "CNY", 1_000_000, 1_000_000, 100, 100, "test");
    String confirmed = UUID.randomUUID().toString();
    costs.bindLimit(confirmed, ModelMode.MOCK, 10_000L);

    // v2 使用 4 倍单价；已确认运行仍应按 v1 结算 200，而不是 800。
    costs.publish("default", "mock", "CNY", 4_000_000, 4_000_000, 100, 100, "test");

    var first = costs.settle(costs.reserve(confirmed, ModelKind.MOCK), 100, 100);
    assertEquals(200L, first.settledMicros());
    var evidence = (Map<?, ?>) ((List<?>) costs.estimate(confirmed).get("RESERVATIONS")).getFirst();
    assertEquals(v1.id(), evidence.get("PRICE_VERSION_ID"));
    int reservationsBefore = ((List<?>) costs.estimate(confirmed).get("RESERVATIONS")).size();
    var unbound =
        assertThrows(
            dev.workbench.framework.control.StopExecution.class,
            () -> costs.reserve(confirmed, ModelKind.DEEPSEEK_FLASH));
    assertEquals(dev.workbench.domain.StopCause.COST_LIMIT, unbound.stopCause());
    assertEquals(
        reservationsBefore, ((List<?>) costs.estimate(confirmed).get("RESERVATIONS")).size());


    String recovered = UUID.randomUUID().toString();
    costs.inheritLimit(confirmed, recovered);
    var inherited = costs.settle(costs.reserve(recovered, ModelKind.MOCK), 100, 100);
    assertEquals(200L, inherited.settledMicros());

    // 对照：确认发生在新价格之后，同样的用量按新价格结算 800。
    String later = UUID.randomUUID().toString();
    costs.bindLimit(later, ModelMode.MOCK, 10_000L);
    var laterSettlement = costs.settle(costs.reserve(later, ModelKind.MOCK), 100, 100);
    assertEquals(800L, laterSettlement.settledMicros());
  }
}
