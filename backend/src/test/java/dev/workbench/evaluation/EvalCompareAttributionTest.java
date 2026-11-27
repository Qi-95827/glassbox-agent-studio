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

  @Test
  void missingAppliedEvidenceIsInsufficientInsteadOfAttributable() {
    when(lab.detail("run-a"))
        .thenReturn(
            detail(
                "run-a",
                Map.of("enableReflection", false, "repeatK", 1),
                Map.of()));
    when(lab.detail("run-b"))
        .thenReturn(
            detail(
                "run-b",
                Map.of("enableReflection", true, "repeatK", 1),
                Map.of()));

    var compared = service.compare("run-a", "run-b");

    assertEquals(false, compared.get("fullyAttributable"));
    @SuppressWarnings("unchecked")
    var warnings = (List<String>) compared.get("attributionWarnings");
    assertEquals(1, warnings.size(), warnings.toString());
    assertTrue(warnings.getFirst().contains("缺少生效证据"), warnings.toString());

    @SuppressWarnings("unchecked")
    var diffs = (List<Map<String, Object>>) compared.get("configDiff");
    var row = diffs.getFirst();
    assertEquals("INSUFFICIENT_EVIDENCE", row.get("attributionStatus"));
    assertEquals(false, row.get("attributable"));
    assertEquals(0, row.get("baselineAppliedCount"));
    assertEquals(1, row.get("baselineTotalCases"));
  }

  @Test
  void partiallyReportedAppliedEvidenceIsStillInsufficientWithAccurateCoverage() {
    when(lab.detail("run-a"))
        .thenReturn(
            detailWithTraces(
                "run-a",
                Map.of("enableReflection", false, "repeatK", 1),
                List.of(Map.of("reflectionApplied", true), Map.of())));
    when(lab.detail("run-b"))
        .thenReturn(
            detailWithTraces(
                "run-b",
                Map.of("enableReflection", true, "repeatK", 1),
                List.of(Map.of("reflectionApplied", true), Map.of())));

    var compared = service.compare("run-a", "run-b");

    assertEquals(false, compared.get("fullyAttributable"));
    @SuppressWarnings("unchecked")
    var diffs = (List<Map<String, Object>>) compared.get("configDiff");
    var row = diffs.getFirst();
    assertEquals("INSUFFICIENT_EVIDENCE", row.get("attributionStatus"));
    assertEquals(1, row.get("baselineAppliedCount"));
    assertEquals(2, row.get("baselineTotalCases"));
    assertEquals(1, row.get("candidateAppliedCount"));
    assertEquals(2, row.get("candidateTotalCases"));
  }

  private static EvalLabService.Detail detail(
      String id, Map<String, Object> config, Map<String, Object> trace) {
    return detailWithTraces(id, config, List.of(trace));
  }

  private static EvalLabService.Detail detailWithTraces(
      String id, Map<String, Object> config, List<Map<String, Object>> traces) {
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
    var samples = new java.util.ArrayList<EvalLabService.CaseView>();
    for (int index = 0; index < traces.size(); index += 1) {
      samples.add(
          new EvalLabService.CaseView(
              id + "-c" + (index + 1),
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
              traces.get(index),
              "",
              50L,
              50,
              50,
              0.0,
              "2026-01-01T00:00:01Z"));
    }
    return new EvalLabService.Detail(run, List.copyOf(samples));
  }
}
