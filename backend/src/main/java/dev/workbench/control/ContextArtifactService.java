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

  /**
   * 运行内的产物回读：给 Agent 的只读工具使用。
   *
   * <p>Agent 运行在异步线程上，没有可用的 HTTP 会话，因此不能用 {@link #read(String)} 那种
   * 会话态归属校验。这里改为**运行内匹配**：产物记录的 {@code runId} 必须与调用方运行完全一致，
   * 这比「同属一个 ownerScope」更窄——只可能读到本运行自己卸载的内容，不可能跨运行或跨账号。
   *
   * <p>返回正文按 {@code maxChars} 截断，并把是否截断显式告知模型，避免把半截正文当成全文。
   */
  @Transactional(readOnly = true)
  public Map<String, Object> readForRun(String runId, String reference, int maxChars) {
    if (runId == null || runId.isBlank())
      throw new IllegalArgumentException("缺少运行标识，不能回读上下文产物。");
    if (reference == null || !reference.matches("art-[0-9a-f]{1,32}"))
      throw new IllegalArgumentException("Artifact 引用格式无效。");
    var artifact =
        artifacts
            .findById(reference)
            .orElseThrow(() -> new IllegalArgumentException("Artifact 不存在或已过期。"));
    if (!runId.equals(artifact.getRunId()))
      throw new IllegalArgumentException("Artifact 不属于当前运行。");
    String content = artifact.getContent() == null ? "" : artifact.getContent();
    int limit = Math.max(1, maxChars);
    boolean truncated = content.length() > limit;
    String body = truncated ? content.substring(0, limit) : content;
    var payload = new java.util.LinkedHashMap<String, Object>();
    payload.put("id", artifact.getId());
    payload.put("title", artifact.getTitle() == null ? "" : artifact.getTitle());
    payload.put("charCount", artifact.getCharCount());
    payload.put("content", body);
    payload.put("truncated", truncated);
    payload.put(
        "outlineJson", artifact.getOutlineJson() == null ? "[]" : artifact.getOutlineJson());
    payload.put("createdAt", artifact.getCreatedAt());
    return java.util.Map.copyOf(payload);
  }
}
