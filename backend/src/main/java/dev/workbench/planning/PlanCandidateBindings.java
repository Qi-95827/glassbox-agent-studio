package dev.workbench.planning;

import dev.workbench.framework.workflow.plan.AgentBinding;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 候选节点的 Agent 绑定投影。
 *
 * <p>生成与人工修订必须写出同一种绑定结构，否则候选被改写后「这个节点用哪个定义」会随入口变化。 这里把投影收成一处，两个入口共用同一份映射规则。
 */
final class PlanCandidateBindings {
  private PlanCandidateBindings() {}

  /** 未绑定私有 Agent 的节点沿用角色对应的内置定义，模型策略继承任务设置。 */
  static Map<String, Object> of(List<PlanCandidateValidator.ResolvedNode> nodes) {
    var value = new LinkedHashMap<String, Object>();
    for (var node : nodes) {
      AgentBinding binding = node.binding();
      value.put(
          node.node().key(),
          binding == null
              ? Map.of(
                  "source",
                  "BUILTIN",
                  "reason",
                  "未指定私有 Agent，沿用角色对应的内置定义。",
                  "definitionId",
                  node.definition().id(),
                  "definitionVersion",
                  node.definition().version(),
                  "name",
                  node.definition().id(),
                  "specSha256",
                  "",
                  "modelStrategy",
                  "INHERIT_TASK",
                  "modelMode",
                  "")
              : binding);
    }
    return value;
  }
}
