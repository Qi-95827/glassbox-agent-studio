package dev.workbench.framework.context;

/**
 * 框架适配层调用上下文压缩的入口。
 *
 * <p>该接口位于纯净契约层，AgentScope 消息的转换与重建由基础设施适配器负责。
 */
@FunctionalInterface
public interface ContextCompactionHook {
  CompactionResult compact(CompactionRequest request);
}
