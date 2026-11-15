package dev.workbench.evaluation;

import dev.workbench.domain.RunStatus;

/** 固定版本种子样本，场景与预算明确，全部使用模拟模型。 */
public enum SeedCase {
  NORMAL("正常协作", "normal", 2, RunStatus.COMPLETED),
  TOOL_FAILURE("工具失败", "fail", 2, RunStatus.FAILED),
  TOOL_LIMIT("工具预算拒绝", "normal", 0, RunStatus.LIMITED),
  REPEAT_LIMIT("重复工具调用保护", "repeat", 5, RunStatus.LIMITED);

  private final String title;
  private final String scenario;
  private final int maxTools;
  private final RunStatus expected;

  SeedCase(String title, String scenario, int maxTools, RunStatus expected) {
    this.title = title;
    this.scenario = scenario;
    this.maxTools = maxTools;
    this.expected = expected;
  }

  /** 案例必须提供的断言集合；新增或变更断言语义须同步版本。 */
  public java.util.Set<EvaluationCheckCode> requiredChecks() {
    return switch (this) {
      case NORMAL ->
          java.util.Set.of(
              EvaluationCheckCode.RUN_STATUS,
              EvaluationCheckCode.PLAN_NODE_COUNT,
              EvaluationCheckCode.PLAN_NODES_COMPLETED,
              EvaluationCheckCode.TOOL_SUCCESS);
      case TOOL_FAILURE ->
          java.util.Set.of(EvaluationCheckCode.RUN_STATUS, EvaluationCheckCode.TOOL_FAILURE);
      case TOOL_LIMIT ->
          java.util.Set.of(
              EvaluationCheckCode.RUN_STATUS,
              EvaluationCheckCode.TOOL_NOT_STARTED,
              EvaluationCheckCode.RUN_TOOL_COUNT_ZERO);
      case REPEAT_LIMIT ->
          java.util.Set.of(
              EvaluationCheckCode.RUN_STATUS,
              EvaluationCheckCode.REPEAT_STOP_CAUSE,
              EvaluationCheckCode.REPEAT_TOOL_COUNT);
    };
  }

  /** 重复调用样本需要足够的模型轮次，避免由其他预算先中断。 */
  public int maxRounds() {
    return this == REPEAT_LIMIT ? 8 : SeedSuite.MAX_ROUNDS;
  }

  /** 仅重复保护样本启用阈值，其他案例保留原有默认值。 */
  public Integer maxIdenticalTools() {
    return this == REPEAT_LIMIT ? 2 : null;
  }

  public String title() {
    return title;
  }

  public String scenario() {
    return scenario;
  }

  public int maxTools() {
    return maxTools;
  }

  public RunStatus expected() {
    return expected;
  }
}
