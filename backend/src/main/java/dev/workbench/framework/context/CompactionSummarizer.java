package dev.workbench.framework.context;

import java.util.List;

/**
 * L2 结构化锚点摘要 SPI。
 *
 * <p>BYOK 模式可由真实 LLM 实现；无模型或 Mock 模式返回不可用，压缩器自动降级为 Query-Aware 的
 * 关键句与实体/路径抽取。实现必须只输出四类结构化锚点，不得把用户密钥或工具凭据写入摘要。
 */
public interface CompactionSummarizer {
  boolean isAvailable();

  /**
   * 把中间历史压缩为强约束结构化检查点。
   *
   * @param focusQuery 当前任务目标或问题；为空时由压缩器自动取最后一条 USER 消息
   * @param previousCheckpoint 上一轮检查点正文；为空表示首次压缩
   * @param messages 本轮新增的中间历史（不含固定锚点与最近成对轮次）
   */
  String summarize(String focusQuery, String previousCheckpoint, List<ContextMessage> messages);

  /**
   * 带运行归属的摘要调用。
   *
   * <p>生产链路必须走这个重载：摘要发生在 Agent 工作线程，没有 HTTP 会话，只有显式的 {@link CompactionScope} 才能告诉我们该用哪个 BYOK
   * 连接、属于哪个运行，避免回退到 {@code LOCAL} 默认连接而盗用他人密钥。默认实现忽略归属，供只关心文本的轻量实现使用。
   *
   * @param scope 运行归属、模型连接与用量回调
   */
  default String summarize(
      CompactionScope scope,
      String focusQuery,
      String previousCheckpoint,
      List<ContextMessage> messages) {
    return summarize(focusQuery, previousCheckpoint, messages);
  }
}
