package dev.workbench.bug;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** 逐用例解析不依赖容器，固定 unittest 输出格式。 */
class UnittestResultsTest {
  private static final String TARGET = "test_calc.TestCalc.test_add";

  @Test
  void recordsFullIdentifierOutcomeAndAssertionPerCase() {
    var report =
        UnittestResults.parse(
            """
            TESTS_DISCOVERED=2
            test_add (test_calc.TestCalc.test_add) ... FAIL
            test_other (test_calc.TestCalc.test_other) ... ok

            ======================================================================
            FAIL: test_add (test_calc.TestCalc.test_add)
            ----------------------------------------------------------------------
            Traceback (most recent call last):
              File "/workspace/tests/test_calc.py", line 5, in test_add
                self.assertEqual(add(2,1),3)
            AssertionError: 1 != 3

            ----------------------------------------------------------------------
            Ran 2 tests in 0.001s

            FAILED (failures=1)
            """);

    assertEquals(2, report.discovered());
    assertEquals(2, report.cases().size());
    var target = report.find(TARGET);
    assertNotNull(target);
    assertEquals(UnittestResults.Outcome.FAILED, target.outcome());
    assertTrue(target.message().contains("AssertionError: 1 != 3"));
    assertEquals(UnittestResults.Outcome.PASSED, report.find("test_calc.TestCalc.test_other").outcome());
  }

  @Test
  void expectedAssertionMustComeFromTargetCase() {
    var report =
        UnittestResults.parse(
            """
            TESTS_DISCOVERED=2
            test_add (test_calc.TestCalc.test_add) ... FAIL
            test_other (test_calc.TestCalc.test_other) ... FAIL

            ======================================================================
            FAIL: test_add (test_calc.TestCalc.test_add)
            ----------------------------------------------------------------------
            Traceback (most recent call last):
              File "/workspace/tests/test_calc.py", line 5, in test_add
                self.assertEqual(add(2,1),4)
            AssertionError: 1 != 4

            ======================================================================
            FAIL: test_other (test_calc.TestCalc.test_other)
            ----------------------------------------------------------------------
            Traceback (most recent call last):
              File "/workspace/tests/test_calc.py", line 7, in test_other
                self.assertEqual(add(2,1),3)
            AssertionError: 1 != 3

            ----------------------------------------------------------------------
            Ran 2 tests in 0.001s

            FAILED (failures=2)
            """);

    assertFalse(report.find(TARGET).message().contains("1 != 3"));
    assertTrue(report.find("test_calc.TestCalc.test_other").message().contains("1 != 3"));
  }

  @Test
  void similarTestNameIsNotTheTargetCase() {
    var report =
        UnittestResults.parse(
            """
            TESTS_DISCOVERED=1
            test_add_extra (test_calc.TestCalc.test_add_extra) ... FAIL

            ======================================================================
            FAIL: test_add_extra (test_calc.TestCalc.test_add_extra)
            ----------------------------------------------------------------------
            AssertionError: 1 != 3

            ----------------------------------------------------------------------
            Ran 1 test in 0.001s

            FAILED (failures=1)
            """);

    assertNull(report.find(TARGET));
    assertEquals(
        UnittestResults.Outcome.FAILED,
        report.find("test_calc.TestCalc.test_add_extra").outcome());
  }

  @Test
  void skippedTargetIsRecordedAsNotExecuted() {
    var report =
        UnittestResults.parse(
            """
            TESTS_DISCOVERED=2
            test_add (test_calc.TestCalc.test_add) ... skipped 'target not ready'
            test_ok (test_calc.TestCalc.test_ok) ... ok

            ----------------------------------------------------------------------
            Ran 2 tests in 0.000s

            OK (skipped=1)
            """);

    var target = report.find(TARGET);
    assertNotNull(target);
    assertEquals(UnittestResults.Outcome.SKIPPED, target.outcome());
    assertTrue(target.message().contains("target not ready"));
  }
}
