package dev.workbench.control;

import dev.workbench.ownership.OwnershipService;
import dev.workbench.persistence.repository.ContextArtifactRepository;
import java.util.Map;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * L1 Artifact 回查：按受控引用读取完整正文与结构化大纲。
 *
 * <p>卸载产物通常包含源码与工具输出全文，回查必须校验运行归属：引用本身不是授权凭证，任何登录用户 都不能仅凭知道引用就读取他人运行的内容。历史无 {@code runId}
 * 的记录一律按不存在处理。
 */
@Service
public class ContextArtifactService {
  private final ContextArtifactRepository artifacts;
  private final OwnershipService ownership;

  public ContextArtifactService(ContextArtifactRepository artifacts, OwnershipService ownership) {
    this.artifacts = artifacts;
    this.ownership = ownership;
  }

  @Transactional(readOnly = true)
  public Map<String, Object> read(String reference) {
    if (reference == null || !reference.matches("art-[0-9a-f]{1,32}"))
      throw new IllegalArgumentException("Artifact 引用格式无效。");
    var artifact =
        artifacts
            .findById(reference)
            .orElseThrow(() -> new IllegalArgumentException("Artifact 不存在或已过期。"));
    String runId = artifact.getRunId();
    if (runId == null || runId.isBlank())
      // 早期记录没有运行归属，无法判定归属，按不存在处理而不是放行。
      throw new EmptyResultDataAccessException(1);
    ownership.requireRun(runId);
    return Map.of(
        "id", artifact.getId(),
        "title", artifact.getTitle(),
        "charCount", artifact.getCharCount(),
        "content", artifact.getContent(),
        "outlineJson", artifact.getOutlineJson() == null ? "[]" : artifact.getOutlineJson(),
        "createdAt", artifact.getCreatedAt());
  }
}
