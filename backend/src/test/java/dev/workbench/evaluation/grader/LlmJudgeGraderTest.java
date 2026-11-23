package dev.workbench.evaluation.grader;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** LLM 裁判严格 JSON 契约：容忍代码围栏，但拒绝缺失或越界的 score。 */
class LlmJudgeGraderTest {
  @Test
  void parsesStrictJsonVerdict() {
    var verdict =
        LlmJudgeGrader.parseVerdict(
            "{\"score\":4,\"passed\":true,\"rubricChecks\":[{\"criterion\":\"保留约束\",\"passed\":true,\"reason\":\"ok\"}],\"summary\":\"通过\"}",
            100,
            20,
            0.02);
    assertEquals("BYOK", verdict.mode());
    assertEquals(4, verdict.score());
    assertTrue(verdict.passed());
    assertEquals(1, verdict.rubricChecks().size());
    assertEquals(0.02, verdict.costUsd());
    assertNull(verdict.error());
  }

  @Test
  void toleratesCodeFenceButRejectsInvalidScore() {
    var fenced =
        LlmJudgeGrader.parseVerdict(
            "```json\n{\"score\":5,\"passed\":true,\"rubricChecks\":[],\"summary\":\"ok\"}\n```",
            10,
            5,
            0);
    assertEquals(5, fenced.score());

    var invalid =
        LlmJudgeGrader.parseVerdict(
            "{\"score\":9,\"passed\":true,\"rubricChecks\":[],\"summary\":\"bad\"}", 0, 0, 0);
    assertNull(invalid.score());
    assertNotNull(invalid.error());
  }

  @Test
  void rejectsNonJsonOutput() {
    var verdict = LlmJudgeGrader.parseVerdict("not json", 0, 0, 0);
    assertNull(verdict.passed());
    assertTrue(verdict.error().contains("严格 JSON"));
  }
}
