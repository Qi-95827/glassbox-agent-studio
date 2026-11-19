package dev.workbench.bug;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

/** 目标断言必须完整匹配，不能被更长断言或前缀命中。 */
class BugReproductionStatusTest {
  private static final String TARGET = "test_calc.TestCalc.test_add";

  @Test
  void exactAssertionLineIsObserved() {
    var report =
        new UnittestResults.Report(
            1,
            List.of(
                new UnittestResults.Case(
                    TARGET, UnittestResults.Outcome.FAILED, "AssertionError: 1 != 3")));
    assertEquals(
        BugService.CheckStatus.FAILURES_OBSERVED, BugService.reproductionStatus(1, report));
  }

  @Test
  void longerActualValueDoesNotMatchExpectedAssertion() {
    var report =
        new UnittestResults.Report(
            1,
            List.of(
                new UnittestResults.Case(
                    TARGET, UnittestResults.Outcome.FAILED, "AssertionError: 1 != 30")));
    assertEquals(BugService.CheckStatus.TARGET_MISMATCH, BugService.reproductionStatus(1, report));
  }

  @Test
  void assertionWithExtraTextDoesNotMatch() {
    var report =
        new UnittestResults.Report(
            1,
            List.of(
                new UnittestResults.Case(
                    TARGET,
                    UnittestResults.Outcome.FAILED,
                    "AssertionError: 1 != 3 (unexpected)")));
    assertEquals(BugService.CheckStatus.TARGET_MISMATCH, BugService.reproductionStatus(1, report));
  }

  @Test
  void exactLineSurvivesAdditionalAssertionDetail() {
    var report =
        new UnittestResults.Report(
            1,
            List.of(
                new UnittestResults.Case(
                    TARGET,
                    UnittestResults.Outcome.FAILED,
                    "AssertionError: 1 != 3\nFirst differing element 0")));
    assertEquals(
        BugService.CheckStatus.FAILURES_OBSERVED, BugService.reproductionStatus(1, report));
  }
}
