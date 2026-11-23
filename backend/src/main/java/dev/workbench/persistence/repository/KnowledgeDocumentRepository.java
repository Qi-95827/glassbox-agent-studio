package dev.workbench.persistence.repository;

import dev.workbench.persistence.entity.KnowledgeDocumentEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** 知识库文档按工作区隔离读取；销毁文档时连同分块一起清理。 */
public interface KnowledgeDocumentRepository extends JpaRepository<KnowledgeDocumentEntity, String> {
  List<KnowledgeDocumentEntity> findByWorkspaceIdOrderByUpdatedAtDescIdAsc(String workspaceId);

  Optional<KnowledgeDocumentEntity> findByIdAndWorkspaceId(String id, String workspaceId);

  Optional<KnowledgeDocumentEntity> findFirstByWorkspaceIdAndSourcePathAndContentSha256(
      String workspaceId, String sourcePath, String contentSha256);

  List<KnowledgeDocumentEntity> findByWorkspaceIdAndSourcePathOrderByUpdatedAtDescIdAsc(
      String workspaceId, String sourcePath);
}
