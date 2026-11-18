package dev.workbench.framework.workflow.plan;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.JsonNode;

/** 节点内所有尝试共用的可选调用上限。 */
public record WorkflowNodeBudget(Integer maxModelCalls, Integer maxToolCalls) {
  public WorkflowNodeBudget {
    if (maxModelCalls == null && maxToolCalls == null)
      throw new IllegalArgumentException("节点预算至少需要设置一项。");
    if (maxModelCalls != null && (maxModelCalls < 1 || maxModelCalls > 20))
      throw new IllegalArgumentException("节点模型调用上限须在 1 至 20 之间。");
    if (maxToolCalls != null && (maxToolCalls < 0 || maxToolCalls > 20))
      throw new IllegalArgumentException("节点工具调用上限须在 0 至 20 之间。");
  }

  @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
  public static WorkflowNodeBudget fromJson(JsonNode value) {
    if (!value.isObject()
        || value.size()
            > (value.has("maxModelCalls") ? 1 : 0) + (value.has("maxToolCalls") ? 1 : 0))
      throw new IllegalArgumentException("节点预算包含未知字段或类型无效。");
    return new WorkflowNodeBudget(
        optionalInteger(value.get("maxModelCalls")), optionalInteger(value.get("maxToolCalls")));
  }

  private static Integer optionalInteger(JsonNode value) {
    if (value == null || value.isNull()) return null;
    if (!value.isIntegralNumber() || !value.canConvertToInt())
      throw new IllegalArgumentException("节点预算必须使用整数。");
    return value.intValue();
  }
}
