package dev.workbench.evaluation;

import java.util.List;

/** 套件相同不代表代码、配置或运行环境一致。 */
public record EvaluationComparison(
    EvaluationBatch baseline,
    EvaluationBatch candidate,
    boolean sameSuite,
    boolean comparableDefinition,
    List<CaseComparison> cases) {
  public EvaluationComparison {
    cases = List.copyOf(cases);
  }
}
