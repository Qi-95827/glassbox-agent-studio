package dev.workbench.framework.workflow.plan;

import dev.workbench.domain.AgentRole;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** 在持久化前校验依赖图及当前执行器支持的受控拓扑。 */
@Component
public class PlanValidator {
  public void validate(PlanDefinition plan, boolean writeAllowed) {
    if (plan == null || plan.nodes().isEmpty()) throw new IllegalArgumentException("任务计划不能为空。");
    Map<AgentRole, PlanDefinition.Node> nodes = new EnumMap<>(AgentRole.class);
    for (var node : plan.nodes()) {
      if (node == null || node.role() == null || nodes.putIfAbsent(node.role(), node) != null)
        throw new IllegalArgumentException("计划节点或角色重复、缺失。");
    }
    plan.workflow();
    if (nodes.containsKey(AgentRole.CODER) && !writeAllowed)
      throw new IllegalArgumentException("未授权写入，不能安排开发角色。");
    var expectedRoles =
        writeAllowed
            ? List.of(AgentRole.ANALYST, AgentRole.CODER, AgentRole.REVIEWER)
            : List.of(AgentRole.ANALYST, AgentRole.REVIEWER);
    if (!plan.nodes().stream().map(PlanDefinition.Node::role).toList().equals(expectedRoles))
      throw new IllegalArgumentException("当前执行器不支持该角色顺序。");
    for (int i = 0; i < plan.nodes().size(); i++) {
      var expected = i == 0 ? List.<AgentRole>of() : List.of(expectedRoles.get(i - 1));
      if (!plan.nodes().get(i).dependencies().equals(expected))
        throw new IllegalArgumentException("当前执行器不支持该依赖结构。");
    }
  }
}
