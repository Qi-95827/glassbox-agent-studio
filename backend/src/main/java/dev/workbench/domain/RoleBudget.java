package dev.workbench.domain;

/** 同一角色的每个 Agent 实例的可选调用上限。 */
@com.fasterxml.jackson.databind.annotation.JsonDeserialize(using = RoleBudgetDeserializer.class)
public record RoleBudget(Integer maxModelCalls, Integer maxToolCalls) {
  public RoleBudget {
    if (maxModelCalls == null && maxToolCalls == null)
      throw new IllegalArgumentException("角色限额至少需要设置一项。");
    if (maxModelCalls != null && (maxModelCalls < 1 || maxModelCalls > 20))
      throw new IllegalArgumentException("角色模型调用上限须在 1 至 20 之间。");
    if (maxToolCalls != null && (maxToolCalls < 0 || maxToolCalls > 20))
      throw new IllegalArgumentException("角色工具调用上限须在 0 至 20 之间。");
  }
}
