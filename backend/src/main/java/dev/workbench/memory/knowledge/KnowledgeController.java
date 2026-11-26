package dev.workbench.memory.knowledge;

import dev.workbench.ownership.OwnershipService;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 工作区知识库 RAG API：索引、列表、检索与删除。
 *
 * <p>每个请求先核对工作区归属；返回体只含来源路径、章节标题与片段，不回传所有者凭据。
 */
@RestController
@RequestMapping("/api/workspaces/{workspaceId}/knowledge")
public class KnowledgeController {
  private final KnowledgeRagService knowledge;
  private final OwnershipService ownership;

  public KnowledgeController(KnowledgeRagService knowledge, OwnershipService ownership) {
    this.knowledge = knowledge;
    this.ownership = ownership;
  }

  @ModelAttribute
  void requireWorkspace(@PathVariable String workspaceId) {
    ownership.requireWorkspace(workspaceId);
  }

  @GetMapping
  public List<KnowledgeRagService.DocumentView> list(@PathVariable String workspaceId) {
    return knowledge.list(workspaceId);
  }

  @PostMapping
  public KnowledgeRagService.IndexResult index(
      @PathVariable String workspaceId, @RequestBody KnowledgeRagService.IndexRequest request) {
    return knowledge.index(workspaceId, request);
  }

  @GetMapping("/search")
  public List<KnowledgeRagService.RagHit> search(
      @PathVariable String workspaceId,
      @RequestParam String q,
      @RequestParam(defaultValue = "5") int limit) {
    return knowledge.retrieve(workspaceId, q, limit);
  }

  /** 工作区切片透视：查看某篇文档被切出的分块边界与预览。 */
  @GetMapping("/{documentId}/chunks")
  public List<KnowledgeRagService.ChunkView> chunks(
      @PathVariable String workspaceId, @PathVariable String documentId) {
    return knowledge.chunks(workspaceId, documentId);
  }

  /**
   * 召回透视：返回单次检索的完整候选与 BM25 / 向量 / RRF 明细。
   *
   * <p>与 {@code /search} 共用同一套阈值与截断规则，区别只是把「没入选的候选以及原因」
   * 也一并返回。工作区归属由 {@code requireWorkspace} 统一校验。
   */
  @GetMapping("/xray")
  public KnowledgeRagService.RetrievalEvidence xray(
      @PathVariable String workspaceId,
      @RequestParam String q,
      @RequestParam(defaultValue = "5") int limit) {
    return knowledge.retrieveWithEvidence(workspaceId, q, limit);
  }

  @DeleteMapping("/{documentId}")
  public Map<String, Object> delete(
      @PathVariable String workspaceId, @PathVariable String documentId) {
    knowledge.delete(workspaceId, documentId);
    return Map.of("deleted", true);
  }
}
