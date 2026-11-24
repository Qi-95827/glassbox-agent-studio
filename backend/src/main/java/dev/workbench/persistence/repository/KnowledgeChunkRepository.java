package dev.workbench.persistence.repository;

import dev.workbench.persistence.entity.KnowledgeChunkEntity;
import dev.workbench.persistence.view.KnowledgeChunkSummary;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 知识库分块按工作区检索；所有查询必须显式携带 workspaceId。 */
public interface KnowledgeChunkRepository extends JpaRepository<KnowledgeChunkEntity, String> {
  List<KnowledgeChunkEntity> findByWorkspaceIdOrderByDocumentIdAscChunkIndexAsc(String workspaceId);

  List<KnowledgeChunkEntity> findByDocumentIdOrderByChunkIndexAsc(String documentId);

  /**
   * 检索预筛投影：只取编号、正文与标题，刻意排除 {@code embedding_json}。
   *
   * <p>整库反序列化 20KB/条向量是此前的 OOM 来源；向量余弦只对候选集按需回表。
   */
  @Query(
      "select c.id as id, c.documentId as documentId, c.sectionTitle as sectionTitle,"
          + " c.content as content, c.tokenEstimate as tokenEstimate"
          + " from KnowledgeChunkEntity c where c.workspaceId = :workspaceId"
          + " order by c.documentId asc, c.chunkIndex asc")
  List<KnowledgeChunkSummary> summarizeByWorkspace(@Param("workspaceId") String workspaceId);

  void deleteByDocumentId(String documentId);
}
