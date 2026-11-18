package dev.workbench.evaluation;

import dev.workbench.domain.RunStatus;

/** 固定版本种子样本，场景与预算明确，全部使用模拟模型。 */
public enum SeedCase {
  NORMAL("正常协作", "normal", 2, RunStatus.COMPLETED),
  TOOL_FAILURE("工具失败", "fail", 2, RunStatus.FAILED),
  TOOL_LIMIT("工具预算拒绝", "normal", 0, RunStatus.LIMITED),
  REPEAT_LIMIT("重复工具调用保护", "repeat", 5, RunStatus.LIMITED),
  DYNAMIC_DUAL_ANALYSIS("同定义节点与汇合", "normal", 0, RunStatus.COMPLETED),
  DYNAMIC_SHARED_BUDGET("动态流程共享模型预算", "normal", 0, RunStatus.LIMITED),
  /** 只读工作区检查通过后继续汇总；使用真实工具版本证据。 */
  DYNAMIC_CHECK_PASS("系统检查通过后继续", "normal", 2, RunStatus.COMPLETED),
  /** Bug 复现缺少 tests 目录：检查阻塞，禁止进入写入与审查。 */
  DYNAMIC_CHECK_BLOCKED("复现缺信息阻塞写入", "normal", 0, RunStatus.BLOCKED);

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
      case DYNAMIC_DUAL_ANALYSIS ->
          java.util.Set.of(
              EvaluationCheckCode.RUN_STATUS, EvaluationCheckCode.DYNAMIC_SAVED_PLAN,
              EvaluationCheckCode.DYNAMIC_INDEPENDENT_INSTANCES,
                  EvaluationCheckCode.DYNAMIC_MERGE_RECEIPTS,
              EvaluationCheckCode.DYNAMIC_MODEL_CALLS, EvaluationCheckCode.TOOL_NOT_STARTED);
      case DYNAMIC_SHARED_BUDGET ->
          java.util.Set.of(
              EvaluationCheckCode.RUN_STATUS, EvaluationCheckCode.DYNAMIC_SAVED_PLAN,
              EvaluationCheckCode.DYNAMIC_INDEPENDENT_INSTANCES,
                  EvaluationCheckCode.DYNAMIC_MODEL_CALLS,
              EvaluationCheckCode.DYNAMIC_BUDGET_STOP, EvaluationCheckCode.TOOL_NOT_STARTED);
      case DYNAMIC_CHECK_PASS ->
          java.util.Set.of(
              EvaluationCheckCode.RUN_STATUS,
              EvaluationCheckCode.DYNAMIC_SAVED_PLAN,
              EvaluationCheckCode.DYNAMIC_CHECK_STATUS,
              EvaluationCheckCode.DYNAMIC_CHECK_EVIDENCE,
              EvaluationCheckCode.DYNAMIC_MODEL_CALLS);
      case DYNAMIC_CHECK_BLOCKED ->
          java.util.Set.of(
              EvaluationCheckCode.RUN_STATUS,
              EvaluationCheckCode.DYNAMIC_SAVED_PLAN,
              EvaluationCheckCode.DYNAMIC_CHECK_STATUS,
              EvaluationCheckCode.DYNAMIC_CHECK_BLOCKED,
              EvaluationCheckCode.DYNAMIC_MODEL_CALLS,
              EvaluationCheckCode.TOOL_NOT_STARTED);
    };
  }

  /** 重复调用样本需要足够的模型轮次，避免由其他预算先中断。 */
  public int maxRounds() {
    return switch (this) {
      case REPEAT_LIMIT -> 8;
      case DYNAMIC_DUAL_ANALYSIS -> 3;
      case DYNAMIC_SHARED_BUDGET -> 2;
      case DYNAMIC_CHECK_PASS -> 4;
      case DYNAMIC_CHECK_BLOCKED -> 1;
      default -> SeedSuite.MAX_ROUNDS;
    };
  }

  public String workflowTemplate() {
    return switch (this) {
      case DYNAMIC_DUAL_ANALYSIS, DYNAMIC_SHARED_BUDGET -> "dual-analysis";
      case DYNAMIC_CHECK_PASS -> "workspace-analysis";
      case DYNAMIC_CHECK_BLOCKED -> "bug-fix";
      default -> null;
    };
  }

  /** 模板版本参与评测快照，模板升级后旧批次保留原定义。 */
  public int templateVersion() {
    return switch (this) {
      case DYNAMIC_CHECK_PASS -> 2;
      case DYNAMIC_CHECK_BLOCKED -> 2;
      default -> 1;
    };
  }

  /** 评测案例需要的临时工作区文件；为空表示不使用工作区。 */
  public String workspaceFixtureFile() {
    return switch (this) {
      case DYNAMIC_CHECK_PASS -> "authorized.txt";
      case DYNAMIC_CHECK_BLOCKED -> "calculator.py";
      default -> null;
    };
  }

  public boolean bugCase() {
    return this == DYNAMIC_CHECK_BLOCKED;
  }

  public boolean writeCase() {
    return this == DYNAMIC_CHECK_BLOCKED;
  }

  public String bugId() {
    return bugCase() ? "MOCK-PY-001" : null;
  }

  public dev.workbench.domain.TaskEntryKind entryKind() {
    return bugCase()
        ? dev.workbench.domain.TaskEntryKind.BUG
        : dev.workbench.domain.TaskEntryKind.GENERAL;
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
