package dev.workbench.evaluation;

import static org.junit.jupiter.api.Assertions.*;

import dev.workbench.app.WorkbenchApplication;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** A/B 消融：配置差异、六维 Δ 与逐题 Diff 均来自两个真实批次。 */
@SpringBootTest(
    classes = WorkbenchApplication.class,
    properties = {
      "spring.datasource.url=jdbc:h2:mem:eval_compare;DB_CLOSE_DELAY=-1",
      "workbench.workspace-root=${java.io.tmpdir}/agent-workbench-eval-compare"
    })
class EvalCompareIntegrationTest {
  @Autowired EvalLabService lab;
  @Autowired EvalCompareService compare;

  @Test
  void comparesRealRunsAndReportsConfigAndPerCaseDiffs() {
    String baseline =
        lab.start(
            new EvalLabService.StartRequest(
                "MINI", "mini", List.of("CONTEXT_COMPACTION"), "mock", null, true, true, false, false,
                1, 1, null, null),
            "zh-CN");
    String candidate =
        lab.start(
            new EvalLabService.StartRequest(
                "MINI", "mini", List.of("CONTEXT_COMPACTION"), "mock", null, false, true, true, true,
                1, 1, null, null),
            "zh-CN");
    wait(baseline);
    wait(candidate);

    Map<String, Object> result = compare.compare(baseline, candidate);
    assertEquals(Boolean.TRUE, result.get("comparable"));
    @SuppressWarnings("unchecked")
    var configDiff = (List<Map<String, Object>>) result.get("configDiff");
    assertTrue(configDiff.stream().anyMatch(row -> "enableCompaction".equals(row.get("field"))));
    assertTrue(configDiff.stream().anyMatch(row -> "enableReflection".equals(row.get("field"))));

    @SuppressWarnings("unchecked")
    var categories = (List<Map<String, Object>>) result.get("categoryComparison");
    assertEquals(6, categories.size());
    assertTrue(categories.stream().anyMatch(row -> "CONTEXT_COMPACTION".equals(row.get("category"))));

    @SuppressWarnings("unchecked")
    var caseDiff = (List<Map<String, Object>>) result.get("caseDiff");
    assertEquals(1, caseDiff.size());
    assertTrue(
        List.of("IMPROVED", "REGRESSED", "UNCHANGED").contains(caseDiff.getFirst().get("status")));

    assertThrows(IllegalArgumentException.class, () -> compare.compare(baseline, baseline));
  }

  private void wait(String id) {
    long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
    while (true) {
      String status = lab.detail(id).run().status();
      if (!status.equals("RUNNING") && !status.equals("QUEUED") && !status.equals("CANCELLING"))
        return;
      if (System.nanoTime() > deadline) fail("批次未结束：" + status);
      try {
        Thread.sleep(100);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        fail("等待被中断");
      }
    }
  }
}
