package dev.workbench.framework.workflow.plan;

import dev.workbench.domain.AgentRole;
import java.util.List;
import org.springframework.stereotype.Component;

/** 将服务端已确认的权限映射为当前执行器支持的计划。 */
@Component
public class PlanFactory {
  private final PlanValidator validator;

  public PlanFactory(PlanValidator validator) {
    this.validator = validator;
  }

  public PlanDefinition create(boolean writeAllowed) {
    var analyst = new PlanDefinition.Node(AgentRole.ANALYST, List.of());
    var coder = new PlanDefinition.Node(AgentRole.CODER, List.of(AgentRole.ANALYST));
    var reviewer =
        new PlanDefinition.Node(
            AgentRole.REVIEWER, List.of(writeAllowed ? AgentRole.CODER : AgentRole.ANALYST));
    var plan =
        new PlanDefinition(
            writeAllowed ? List.of(analyst, coder, reviewer) : List.of(analyst, reviewer));
    validator.validate(plan, writeAllowed);
    return plan;
  }
}
