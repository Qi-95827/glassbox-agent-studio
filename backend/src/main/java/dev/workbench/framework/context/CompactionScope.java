package dev.workbench.framework.context;

/**
 * 一次上下文压缩调用的归属上下文。
 *
 * <p>四级压缩运行在 Agent 工作线程里，通常没有 HTTP 会话：如果不显式透传运行归属，后台代码只能 回退到 {@code LOCAL} 默认连接，从而用错他人的 BYOK
 * 密钥、把敏感对话发往错误账号并绕过运行预算。
 *
 * @param runId 当前运行标识；用于 L1 产物的按运行隔离与回查归属校验
 * @param ownerScopeKey 运行所属账号的执行归属键；仅作审计与隔离用途，不参与凭据解密
 * @param modelConnectionId 当前运行冻结的用户模型连接；为空表示该运行没有可用 BYOK 连接
 * @param usage 辅助调用用量回调；未接入时为空实现
 */
public record CompactionScope(
    String runId, String ownerScopeKey, String modelConnectionId, CompactionUsageSink usage) {
  /** 未绑定运行归属的空上下文；只有历史调用与单元测试会用到。 */
  public static final CompactionScope NONE =
      new CompactionScope(null, null, null, CompactionUsageSink.NONE);

  public CompactionScope {
    usage = usage == null ? CompactionUsageSink.NONE : usage;
    modelConnectionId =
        modelConnectionId == null || modelConnectionId.isBlank() ? null : modelConnectionId;
  }

  /** 是否绑定了可用的用户模型连接。 */
  public boolean hasConnection() {
    return modelConnectionId != null;
  }

  /** 绑定用量回调后的副本。 */
  public CompactionScope withUsage(CompactionUsageSink value) {
    return new CompactionScope(runId, ownerScopeKey, modelConnectionId, value);
  }
}
