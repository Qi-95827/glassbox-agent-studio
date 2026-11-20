package dev.workbench.planning;

import java.util.List;
import java.util.Map;

/**
 * 发起规划时冻结的私有 Agent 短引用映射。
 *
 * <p>模型只看到 P1 等短引用；服务端在这里保存对应精确版本和摘要。收到响应和确认时沿用同一份映射，
 * 等待期间发布新版本不会把同一建议改成 v2。
 */
public record PlanningAgentSnapshot(
    Map<String, Alias> aliases, ManualChoice manual, List<String> errors) {
  public PlanningAgentSnapshot {
    aliases = aliases == null ? Map.of() : Map.copyOf(aliases);
    errors = errors == null ? List.of() : List.copyOf(errors);
  }

  public record Alias(
      String alias,
      String id,
      int version,
      String name,
      String permissionRole,
      String description,
      List<String> tools,
      String specSha256) {
    public Alias {
      tools = tools == null ? List.of() : List.copyOf(tools);
    }
  }

  public record ManualChoice(
      String id, int version, String name, String specSha256) {}
}
