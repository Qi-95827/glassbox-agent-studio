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

  /**
   * 只差 {@code enableCompaction} 的两个真实批次必须完全可归因。
   *
   * <p>回归：压缩 Runner 此前不写 {@code compactionApplied}，Ab 对照恒落 {@code INSUFFICIENT_EVIDENCE}；
   * 关闭压缩那一侧还会因水位参数非法直接抛错，等于「未下推」和「没用上」被混为一谈。
   */
  @Test
  void compactionOnlyComparisonIsFullyAttributable() {
    String baseline =
        lab.start(
            new EvalLabService.StartRequest(
                "MINI", "mini", List.of("CONTEXT_COMPACTION"), "mock", null, false, false, false,
                false, 1, 1, null, null),
            "zh-CN");
    String candidate =
        lab.start(
            new EvalLabService.StartRequest(
                "MINI", "mini", List.of("CONTEXT_COMPACTION"), "mock", null, true, false, false,
                false, 1, 1, null, null),
            "zh-CN");
    wait(baseline);
    wait(candidate);

    Map<String, Object> result = compare.compare(baseline, candidate);

    assertEquals(Boolean.TRUE, result.get("fullyAttributable"), "仅差压缩开关时必须完全可归因：" + result);
    @SuppressWarnings("unchecked")
    var warnings = (List<Object>) result.get("attributionWarnings");
    assertTrue(warnings.isEmpty(), "不应再出现证据不足告警：" + warnings);

    @SuppressWarnings("unchecked")
    var configDiff = (List<Map<String, Object>>) result.get("configDiff");
    var row =
        configDiff.stream()
            .filter(item -> "enableCompaction".equals(item.get("field")))
            .findFirst()
            .orElseThrow(() -> new AssertionError("缺少 enableCompaction 差异行：" + configDiff));
    assertEquals("ATTRIBUTABLE", row.get("attributionStatus"), row.toString());
    assertEquals(Boolean.TRUE, row.get("attributable"), row.toString());
    assertEquals(Boolean.TRUE, row.get("baselineApplied"), row.toString());
    assertEquals(Boolean.TRUE, row.get("candidateApplied"), row.toString());
    assertEquals(row.get("baselineTotalCases"), row.get("baselineAppliedCount"), row.toString());
    assertEquals(row.get("candidateTotalCases"), row.get("candidateAppliedCount"), row.toString());
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
