package dev.workbench.evaluation.grader;

import static org.junit.jupiter.api.Assertions.*;

import dev.workbench.framework.evaluation.EvalCaseExecution;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 确定性代码裁判：真实证据逐条判定，未知断言失败关闭。 */
class CodeAssertionGraderTest {
  private final CodeAssertionGrader grader = new CodeAssertionGrader();

  @Test
  void passesOnlyWhenEveryDeclaredAssertionHolds() {
    var execution =
        new EvalCaseExecution(
            Map.of(
                "needleRetention", 1.0,
                "candidateLeaked", false,
                "toolCalls", List.of(Map.of("name", "workspace_read", "status", "SUCCEEDED"))),
            Map.of(),
            Map.of(),
            null,
            "",
            10,
            0,
            0,
            0);
    var graded =
        grader.grade(
            List.of(
                Map.of("type", "NEEDLE_RETENTION_MIN", "expected", 1.0),
                Map.of("type", "CANDIDATE_NOT_LEAKED", "expected", true),
                Map.of("type", "TOOL_CALLED", "expected", "workspace_read")),
            execution);
    assertTrue(graded.passed());
    assertEquals(1.0, graded.score());
    assertEquals(3, graded.assertions().size());
  }

  @Test
  void failsClosedOnUnknownAssertionAndLeakedCandidate() {
    var execution =
        new EvalCaseExecution(
            Map.of("candidateLeaked", true), Map.of(), Map.of(), null, "", 1, 0, 0, 0);
    var graded =
        grader.grade(
            List.of(
                Map.of("type", "CANDIDATE_NOT_LEAKED", "expected", true),
                Map.of("type", "NOT_A_REAL_ASSERTION", "expected", true)),
            execution);
    assertFalse(graded.passed());
    assertEquals(0.0, graded.score());
    assertFalse((Boolean) graded.assertions().get(0).get("passed"));
    assertEquals("UNKNOWN_ASSERTION", graded.assertions().get(1).get("actual"));
  }

  @Test
  void emptyAssertionsNeverScoreAPass() {
    var graded =
        grader.grade(List.of(), new EvalCaseExecution(Map.of(), Map.of(), Map.of(), null, "", 0, 0, 0, 0));
    assertFalse(graded.passed());
    assertEquals(0.0, graded.score());
  }
}
