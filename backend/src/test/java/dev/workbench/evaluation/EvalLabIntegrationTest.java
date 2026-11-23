package dev.workbench.evaluation;

import static org.junit.jupiter.api.Assertions.*;

import dev.workbench.app.WorkbenchApplication;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** 批次编排：抽样、执行、真实汇总与逐题落库。 */
@SpringBootTest(
    classes = WorkbenchApplication.class,
    properties = {
      "spring.datasource.url=jdbc:h2:mem:eval_lab_batch;DB_CLOSE_DELAY=-1",
      "workbench.workspace-root=${java.io.tmpdir}/agent-workbench-eval-lab-batch"
    })
class EvalLabIntegrationTest {
  @Autowired EvalLabService lab;
  @Autowired dev.workbench.evaluation.dataset.BuiltInMiniPackLoader packs;

  @Test
  void builtInPacksContainFourRealSamplesPerCategory() {
    var counts = packs.countsByCategory();
    assertEquals(6, counts.size());
    counts.forEach((category, count) -> assertEquals(4, count, category.name()));
    assertEquals(24, packs.loadAll().size());
  }

  @Test
  void miniBatchPersistsRealPerCaseMetricsAndSixDimSummary() {
    String id =
        lab.start(
            new EvalLabService.StartRequest(
                "MINI",
                null,
                List.of("CONTEXT_COMPACTION", "SAFETY_INJECTION"),
                "mock",
                null,
                true,
                true,
                false,
                false,
                1,
                1,
                null,
                null),
            "zh-CN");
    var detail = waitFor(id);
    assertEquals("COMPLETED", detail.run().status());
    assertEquals(2, detail.cases().size());
    assertNotNull(detail.run().summary());
    @SuppressWarnings("unchecked")
    var categories = (Map<String, Object>) detail.run().summary().get("categories");
    assertNotNull(categories.get("CONTEXT_COMPACTION"));
    assertNotNull(categories.get("SAFETY_INJECTION"));
    var safety = (Map<?, ?>) categories.get("SAFETY_INJECTION");
    assertEquals(1.0, safety.get("passRate"));
    var contextCase =
        detail.cases().stream()
            .filter(item -> item.category().equals("CONTEXT_COMPACTION"))
            .findFirst()
            .orElseThrow();
    assertFalse(contextCase.assertions().isEmpty());
    assertTrue(contextCase.metrics().containsKey("compactionTier"));
  }

  @Test
  void metaExposesAllSixMiniCategories() {
    var meta = lab.meta();
    @SuppressWarnings("unchecked")
    var categories = (List<Map<String, Object>>) meta.get("categories");
    assertEquals(6, categories.size());
    assertTrue(categories.stream().allMatch(row -> ((Number) row.get("miniCount")).intValue() == 4));
  }

  private EvalLabService.Detail waitFor(String id) {
    long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
    while (true) {
      var detail = lab.detail(id);
      String status = detail.run().status();
      if (!status.equals("RUNNING") && !status.equals("QUEUED") && !status.equals("CANCELLING"))
        return detail;
      if (System.nanoTime() > deadline) fail("评测批次未结束：" + status);
      try {
        Thread.sleep(100);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        fail("等待被中断");
      }
    }
  }
}
