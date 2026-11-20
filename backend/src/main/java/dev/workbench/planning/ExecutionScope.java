package dev.workbench.planning;

import java.util.Objects;

/**
 * 规划、确认和证据共用的执行归属。
 *
 * <p>本期只解析为本地作用域，接口预留是为了以后由已认证会话、成员关系或项目权限决定归属； HTTP 客户端提交的租户字段、自定义 Header 或模型输出都不能直接决定该值。
 */
public record ExecutionScope(String ownerScopeKey, String actorKey) {
  /** 个人本地部署的固定归属；所有本期记录都写入这个值。 */
  public static final ExecutionScope LOCAL = new ExecutionScope("LOCAL", "LOCAL");

  public ExecutionScope {
    requireKey(ownerScopeKey, "执行归属");
    requireKey(actorKey, "操作者");
  }

  private static void requireKey(String value, String name) {
    Objects.requireNonNull(value, name + "不能为空");
    if (!value.matches("[A-Z0-9_-]{1,80}")) throw new IllegalArgumentException(name + "编码无效。");
  }
}
