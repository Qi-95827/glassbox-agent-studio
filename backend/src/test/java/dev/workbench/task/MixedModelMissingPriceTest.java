package dev.workbench.task;

import static org.junit.jupiter.api.Assertions.*;

import dev.workbench.app.WorkbenchApplication;
import dev.workbench.domain.ModelKind;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** 预算确认前必须为所有实际模型找到生效价格。 */
@SpringBootTest(
    classes = WorkbenchApplication.class,
    properties = {
      "spring.datasource.url=jdbc:h2:mem:mixedmodelmissingprice;DB_CLOSE_DELAY=-1",
      "workbench.workspace-root=${java.io.tmpdir}/agent-workbench-missing-price"
    })
class MixedModelMissingPriceTest {
  @Autowired CostService costs;

  @Test
  void missingActualModelPriceIsRejectedBeforeRunCreation() {
    var error =
        assertThrows(
            IllegalArgumentException.class,
            () -> costs.requireBudgetPricing(List.of(ModelKind.DEEPSEEK_FLASH), 10_000L));
    assertTrue(error.getMessage().contains("缺少生效价格"));
  }
}
