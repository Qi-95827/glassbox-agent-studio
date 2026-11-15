package dev.workbench.evaluation;

/** 单条已执行的固定断言；说明只包含系统状态或计数。 */
public record EvaluationCheck(EvaluationCheckCode code, boolean passed, String detail) {}
