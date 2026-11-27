package dev.workbench.evaluation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A/B 消融主对照的生效归因测试。
 *
 * <p>回归：CodingReflectionRunner 已记录 reflectionApplied=false / compactionApplied=false，但主对照此前
 * 仍直接拿请求配置做 configDiff，未标明「未生效、不可归因」。
 */
class EvalCompareAttributionTest {
  private final EvalLabService lab = mock(EvalLabService.class);
  private final EvalCompareService service = new EvalCompareService(lab);

  @Test
  void marksUnappliedSwitchDiffAsNonAttributableAndEmitsWarning() {
    when(lab.detail("run-a"))
        .thenReturn(
            detail(
                "run-a",
                Map.of("enableReflection", false, "enableCompaction", false, "repeatK", 1),
                Map.of("reflectionApplied", false, "compactionApplied", false)));
    when(lab.detail("run-b"))
        .thenReturn(
            detail(
                "run-b",
                Map.of("enableReflection", true, "enableCompaction", false, "repeatK", 1),
                Map.of("reflectionApplied", false, "compactionApplied", false)));

    var compared = service.compare("run-a", "run-b");

    assertEquals(false, compared.get("fullyAttributable"));
    @SuppressWarnings("unchecked")
    var warnings = (List<String>) compared.get("attributionWarnings");
    assertEquals(1, warnings.size(), warnings.toString());
    assertTrue(warnings.getFirst().contains("reflectionApplied=false"));

    @SuppressWarnings("unchecked")
    var diffs = (List<Map<String, Object>>) compared.get("configDiff");
    assertEquals(1, diffs.size());
    var row = diffs.getFirst();
    assertEquals("enableReflection", row.get("field"));
    assertEquals(false, row.get("baselineApplied"));
    assertEquals(false, row.get("candidateApplied"));
    assertEquals(false, row.get("attributable"));
  }

  @Test
  void marksAppliedSwitchDiffAsAttributableWithoutWarnings() {
    when(lab.detail("run-a"))
        .thenReturn(
            detail(
                "run-a",
                Map.of("enableCompaction", false, "repeatK", 1),
                Map.of("compactionApplied", true)));
    when(lab.detail("run-b"))
        .thenReturn(
            detail(
                "run-b",
                Map.of("enableCompaction", true, "repeatK", 1),
                Map.of("compactionApplied", true)));

    var compared = service.compare("run-a", "run-b");

    assertEquals(true, compared.get("fullyAttributable"));
    @SuppressWarnings("unchecked")
    var warnings = (List<String>) compared.get("attributionWarnings");
    assertTrue(warnings.isEmpty(), warnings.toString());

    @SuppressWarnings("unchecked")
    var diffs = (List<Map<String, Object>>) compared.get("configDiff");
    assertEquals(1, diffs.size());
    assertEquals(true, diffs.getFirst().get("attributable"));
    assertEquals(true, diffs.getFirst().get("baselineApplied"));
    assertEquals(true, diffs.getFirst().get("candidateApplied"));
  }

  private static EvalLabService.Detail detail(
      String id, Map<String, Object> config, Map<String, Object> trace) {
    var run =
        new EvalLabService.RunView(
            id,
            "COMPLETED",
            "MINI",
            "mini",
            List.of("CODING_REFLECTION"),
            config,
            Map.of(
                "overall",
                Map.of("passRate", 1.0, "score", 100.0, "total", 1, "passed", 1, "avgCostUsd", 0.0),
                "categories",
                Map.of(
                    "CODING_REFLECTION",
                    Map.of("score", 100.0, "passRate", 1.0, "total", 1))),
            false,
            0.0,
            100,
            50L,
            null,
            "zh-CN",
            "2026-01-01T00:00:00Z",
            "2026-01-01T00:00:00Z",
            "2026-01-01T00:00:01Z");
    var sample =
        new EvalLabService.CaseView(
            id + "-c1",
            "CODING_REFLECTION",
            "s1",
            "样例",
            "Sample",
            "mini",
            1,
            null,
            true,
            1.0,
            null,
            Map.of(),
            List.of(),
            Map.of(),
            trace,
            "",
            50L,
            50,
            50,
            0.0,
            "2026-01-01T00:00:01Z");
    return new EvalLabService.Detail(run, List.of(sample));
  }
}
