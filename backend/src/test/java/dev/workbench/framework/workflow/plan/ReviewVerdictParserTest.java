package dev.workbench.framework.workflow.plan;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** 审查结论解析：JSON 代码块、DECISION 标记与保守默认。 */
class ReviewVerdictParserTest {
  @Test
  void parsesFencedJsonWithDefects() {
    var verdict =
        ReviewVerdictParser.parse(
            """
            DECISION: REVISE_REQUIRED
            ```json
            {"decision":"REVISE_REQUIRED","summary":"缺少证据","defects":[
              {"severity":"MAJOR","filePath":"a.js","summary":"未验证","suggestedFix":"补充测试"}]}
            ```
            """);
    assertTrue(verdict.reviseRequired());
    assertEquals(1, verdict.defects().size());
    assertEquals("a.js", verdict.defects().getFirst().filePath());
    assertTrue(verdict.feedbackText().contains("补充测试"));
  }

  @Test
  void parsesDecisionLineWithoutJson() {
    assertEquals(
        ReviewVerdict.APPROVED, ReviewVerdictParser.parse("DECISION: APPROVED\nLooks good").decision());
    assertEquals(
        ReviewVerdict.REVISE_REQUIRED,
        ReviewVerdictParser.parse("结论：REVISE_REQUIRED\n需要修改").decision());
  }

  @Test
  void defaultsToApprovedWhenNoStructuredMarkerExists() {
    assertEquals(ReviewVerdict.APPROVED, ReviewVerdictParser.parse("普通审查意见").decision());
    assertEquals(ReviewVerdict.APPROVED, ReviewVerdictParser.parse("").decision());
  }

  @Test
  void revisionEdgeRejectsUnboundedOrEmptyTargets() {
    assertThrows(IllegalArgumentException.class, () -> new RevisionEdge("review", "impl", 0));
    assertThrows(IllegalArgumentException.class, () -> new RevisionEdge("review", "impl", 6));
    assertThrows(IllegalArgumentException.class, () -> new RevisionEdge("", "impl", 1));
  }
}
