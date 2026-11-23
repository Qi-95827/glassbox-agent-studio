package dev.workbench.persistence.artifact;

import dev.workbench.domain.WorkbenchTime;
import dev.workbench.framework.context.ContextArtifactStore;
import dev.workbench.persistence.entity.ContextArtifactEntity;
import dev.workbench.persistence.repository.ContextArtifactRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

/**
 * L1 卸载产物落盘实现。
 *
 * <p>以内容 SHA-256 前 16 位生成确定性引用，重复卸载同一正文不会产生无界重复数据；
 * 只保存工具正文与结构化大纲，不保存密钥。
 */
@Component
public class JpaContextArtifactStore implements ContextArtifactStore {
  private final ContextArtifactRepository artifacts;

  public JpaContextArtifactStore(ContextArtifactRepository artifacts) {
    this.artifacts = artifacts;
  }

  @Override
  public boolean available() {
    return true;
  }

  @Override
  public String save(Artifact artifact) {
    if (artifact == null || artifact.content() == null) return null;
    String id = reference(artifact.content(), artifact.title());
    if (artifacts.existsById(id)) return id;
    try {
      artifacts.save(
          new ContextArtifactEntity(
              id,
              artifact.runId(),
              artifact.toolCallId(),
              artifact.title() == null ? "该工具输出" : artifact.title(),
              artifact.content(),
              artifact.outlineJson(),
              WorkbenchTime.now()));
      return id;
    } catch (RuntimeException e) {
      // 落盘失败不阻断压缩；消息体仍保留确定性引用与摘录。
      return null;
    }
  }

  /** 确定性引用：内容哈希 + 标题，不含用户 ID 或路径明文。 */
  static String reference(String content, String title) {
    try {
      var digest =
          MessageDigest.getInstance("SHA-256")
              .digest((title + "\n" + content).getBytes(StandardCharsets.UTF_8));
      return "art-" + HexFormat.of().formatHex(digest).substring(0, 16);
    } catch (Exception e) {
      return "art-" + Integer.toHexString((title + content).hashCode());
    }
  }
}
