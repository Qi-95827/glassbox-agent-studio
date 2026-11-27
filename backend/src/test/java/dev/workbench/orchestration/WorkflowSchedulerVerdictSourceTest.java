package dev.workbench.orchestration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** 审查裁决必须来自未截断正文；展示用摘要不得反向决定是否返工。 */
class WorkflowSchedulerVerdictSourceTest {
  @Test
  void verdictUsesFullOutputEvenWhenTheDisplaySummaryIsTruncated() {
    String full = "前文".repeat(1200) + "\nDECISION: REVISE_REQUIRED";
    String display = full.substring(0, 1899) + "…";

    assertEquals(full, WorkflowScheduler.verdictSource(display, full));
    assertTrue(!display.contains("REVISE_REQUIRED"), "摘要里确实已经被截掉标记");
  }

  @Test
  void fallsBackToDisplayResultWhenNoFullOutputIsAvailable() {
    assertEquals("摘要", WorkflowScheduler.verdictSource("摘要", null));
    assertEquals("摘要", WorkflowScheduler.verdictSource("摘要", "  "));
  }
}
