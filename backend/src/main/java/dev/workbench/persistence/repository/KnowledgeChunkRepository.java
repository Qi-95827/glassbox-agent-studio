package dev.workbench.persistence.repository;

import dev.workbench.persistence.entity.KnowledgeChunkEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** 知识库分块按工作区检索；所有查询必须显式携带 workspaceId。 */
public interface KnowledgeChunkRepository extends JpaRepository<KnowledgeChunkEntity, String> {
  List<KnowledgeChunkEntity> findByWorkspaceIdOrderByDocumentIdAscChunkIndexAsc(String workspaceId);

  List<KnowledgeChunkEntity> findByDocumentIdOrderByChunkIndexAsc(String documentId);

  void deleteByDocumentId(String documentId);
}
