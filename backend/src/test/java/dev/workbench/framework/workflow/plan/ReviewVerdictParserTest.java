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
    // 宽松默认只服务非反思场景；反思节点必须用 parseStructured 的空结果走保守策略。
    assertTrue(ReviewVerdictParser.parseStructured("普通审查意见").isEmpty());
    assertTrue(ReviewVerdictParser.parseStructured("").isEmpty());
    assertTrue(ReviewVerdictParser.parseStructured("the change was approved by tests").isEmpty());
  }

  @Test
  void structuredParserRecognisesExplicitMarkersOnly() {
    assertEquals(
        ReviewVerdict.APPROVED,
        ReviewVerdictParser.parseStructured("DECISION: APPROVED\nLooks good").orElseThrow().decision());
    assertEquals(
        ReviewVerdict.REVISE_REQUIRED,
        ReviewVerdictParser.parseStructured("结论：REVISE_REQUIRED\n需要修改").orElseThrow().decision());
    assertEquals(
        ReviewVerdict.REVISE_REQUIRED,
        ReviewVerdictParser.parseStructured("结论 APPROVED 与 REVISE_REQUIRED 同时出现时以返工为准")
            .orElseThrow()
            .decision());
    // 下划线相连的标识符（如 NO_REVISE_REQUIRED）不是独立裁决标记，不得误触发返工。
    assertTrue(ReviewVerdictParser.parseStructured("状态：NO_REVISE_REQUIRED").isEmpty());
    assertTrue(ReviewVerdictParser.parseStructured("NOT_REVISE_REQUIRED / REVISE_REQUIRED_FALSE").isEmpty());
  }

  @Test
  void longReviewOutputWithTrailingRevisionMarkerIsNotSilentlyApproved() {
    // 回归：>2500 字正文里，REVISE_REQUIRED 只出现在末尾。旧实现先截断到 1900 字再解析，标记被切掉后默认 APPROVED。
    String detailed = "逐条核对：" + "证据不足需要补充。".repeat(320);
    String full = detailed + "\nDECISION: REVISE_REQUIRED\n请先补齐回归测试。";
    assertTrue(full.length() > 2500, "用例必须真的超过旧截断阈值");

    assertEquals(ReviewVerdict.REVISE_REQUIRED, ReviewVerdictParser.parse(full).decision());
    assertEquals(
        ReviewVerdict.REVISE_REQUIRED,
        ReviewVerdictParser.parseStructured(full).orElseThrow().decision());
  }

  @Test
  void revisionEdgeRejectsUnboundedOrEmptyTargets() {
    assertThrows(IllegalArgumentException.class, () -> new RevisionEdge("review", "impl", 0));
    assertThrows(IllegalArgumentException.class, () -> new RevisionEdge("review", "impl", 6));
    assertThrows(IllegalArgumentException.class, () -> new RevisionEdge("", "impl", 1));
  }
}
