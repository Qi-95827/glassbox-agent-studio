package dev.workbench.framework.context;

/**
 * L1 卸载产物落盘端口。
 *
 * <p>完整正文写入 {@code context_artifacts} 后，消息体只保留受控 {@code artifactRef} 与高相关摘录；
 * 需要时可凭引用回查全文。实现不可记录任何凭据。
 *
 * <p>引用必须按运行与归属隔离：不同用户/不同运行即使卸载了完全相同的工具正文，也不能共用同一条 记录，否则任意用户凭内容哈希即可读取他人源码与工具输出。
 */
public interface ContextArtifactStore {
  boolean available();

  /** 保存完整正文与结构化大纲，返回受控引用；失败时返回 null，由压缩器生成确定性 ref。 */
  String save(Artifact artifact);

  /**
   * 一次卸载产物。
   *
   * @param runId 所属运行；回查时据此校验归属
   * @param ownerScopeKey 所属账号的执行归属键；用于生成跨用户不冲突的引用
   * @param toolCallId 触发卸载的工具调用标识
   * @param title 稳定标题（工具名）
   * @param content 完整正文
   * @param outlineJson 结构化大纲
   */
  record Artifact(
      String runId,
      String ownerScopeKey,
      String toolCallId,
      String title,
      String content,
      String outlineJson) {}
}
