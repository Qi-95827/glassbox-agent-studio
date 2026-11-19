package dev.workbench.persistence.repository;

import dev.workbench.persistence.entity.WorkspaceDeclaredFileEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/** 显式声明文件按 (workspace_id, path) 复合键读写。 */
public interface WorkspaceDeclaredFileRepository
    extends JpaRepository<WorkspaceDeclaredFileEntity, WorkspaceDeclaredFileEntity.Key> {
  @Query(
      "select f from WorkspaceDeclaredFileEntity f"
          + " where f.id.workspaceId = :workspaceId order by f.id.path")
  List<WorkspaceDeclaredFileEntity> findByWorkspaceId(@Param("workspaceId") String workspaceId);

  @Transactional
  @Modifying
  @Query(
      "delete from WorkspaceDeclaredFileEntity f"
          + " where f.id.workspaceId = :workspaceId and f.id.path = :path")
  int deleteForWorkspace(
      @Param("workspaceId") String workspaceId, @Param("path") String path);
}
