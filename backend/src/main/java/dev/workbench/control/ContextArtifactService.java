package dev.workbench.control;

import dev.workbench.persistence.repository.ContextArtifactRepository;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** L1 Artifact 回查：按受控引用读取完整正文与结构化大纲。 */
@Service
public class ContextArtifactService {
  private final ContextArtifactRepository artifacts;

  public ContextArtifactService(ContextArtifactRepository artifacts) {
    this.artifacts = artifacts;
  }

  @Transactional(readOnly = true)
  public Map<String, Object> read(String reference) {
    if (reference == null || !reference.matches("art-[0-9a-f]{1,32}"))
      throw new IllegalArgumentException("Artifact 引用格式无效。");
    var artifact =
        artifacts
            .findById(reference)
            .orElseThrow(() -> new IllegalArgumentException("Artifact 不存在或已过期。"));
    return Map.of(
        "id", artifact.getId(),
        "title", artifact.getTitle(),
        "charCount", artifact.getCharCount(),
        "content", artifact.getContent(),
        "outlineJson", artifact.getOutlineJson() == null ? "[]" : artifact.getOutlineJson(),
        "createdAt", artifact.getCreatedAt());
  }
}
