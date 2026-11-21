package dev.workbench.framework.control;

import dev.workbench.domain.RoleBudget;
import dev.workbench.domain.ToolKind;
import dev.workbench.framework.workflow.plan.WorkflowNodeBudget;
import dev.workbench.domain.RunLimits;
import java.util.Map;

/** 运行控制器实际应用于当前实例的限额及策略版本。 */
public record AgentBudgetPolicy(
    int version,
    RunLimits limits,
    RoleBudget roleLimits,
    Map<ToolKind, Integer> toolLimits,
    WorkflowNodeBudget nodeLimits) {
  public AgentBudgetPolicy {
    toolLimits = Map.copyOf(toolLimits);
  }
}
