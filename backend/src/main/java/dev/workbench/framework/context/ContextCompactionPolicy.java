package dev.workbench.framework.context;

/** 四级分层上下文压缩策略；实现必须保持 Pinned Anchor 与最近成对消息不被拆散。 */
public interface ContextCompactionPolicy {
  CompactionResult evaluateAndCompact(CompactionRequest request);
}
