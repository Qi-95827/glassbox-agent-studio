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
 * <p>引用由「归属 + 运行 + 内容 SHA-256」共同派生：同一运行内重复卸载同一正文不会产生无界重复数据，
 * 而不同用户或不同运行即使内容完全相同也会得到不同引用，杜绝凭内容哈希跨账号读取他人工具正文。 只保存工具正文与结构化大纲，不保存密钥。
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
    if (artifact.runId() == null || artifact.runId().isBlank()) {
      // 没有运行归属的产物无法通过回查接口做归属校验，直接不落盘，避免产生不可审计的孤儿正文。
      return null;
    }
    String id =
        reference(artifact.ownerScopeKey(), artifact.runId(), artifact.content(), artifact.title());
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

  /** 确定性引用：归属 + 运行 + 标题 + 内容哈希，不含用户 ID 或路径明文。 */
  static String reference(String ownerScopeKey, String runId, String content, String title) {
    String seed =
        (ownerScopeKey == null ? "" : ownerScopeKey)
            + '\u0000'
            + (runId == null ? "" : runId)
            + '\u0000'
            + (title == null ? "" : title)
            + '\u0000'
            + (content == null ? "" : content);
    try {
      var digest =
          MessageDigest.getInstance("SHA-256").digest(seed.getBytes(StandardCharsets.UTF_8));
      return "art-" + HexFormat.of().formatHex(digest).substring(0, 16);
    } catch (Exception e) {
      return "art-" + Integer.toHexString(seed.hashCode());
    }
  }
}
