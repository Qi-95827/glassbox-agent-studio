package dev.workbench.framework.context;

/**
 * L1 卸载产物落盘端口。
 *
 * <p>完整正文写入 {@code context_artifacts} 后，消息体只保留受控 {@code artifactRef} 与高相关摘录；
 * 需要时可凭引用回查全文。实现不可记录任何凭据。
 */
public interface ContextArtifactStore {
  boolean available();

  /** 保存完整正文与结构化大纲，返回受控引用；失败时返回 null，由压缩器生成确定性 ref。 */
  String save(Artifact artifact);

  record Artifact(
      String runId,
      String toolCallId,
      String title,
      String content,
      String outlineJson) {}
}
