package dev.workbench.framework.evaluation;

/**
 * 六类评测执行器 SPI。
 *
 * <p>实现必须真实驱动系统既有组件（工作流调度、上下文压缩、长期记忆检索、ToolGateway、计划校验器），
 * 不得直接编造指标。任何基础设施异常应抛出运行时异常，由批次服务记录为失败案例。
 */
public interface EvalCategoryRunner {
  EvalCategory category();

  EvalCaseExecution run(EvalSample sample, EvalExperimentConfig config, String ownerScopeKey);
}
