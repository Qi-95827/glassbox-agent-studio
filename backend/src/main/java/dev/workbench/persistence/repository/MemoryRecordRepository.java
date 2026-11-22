package dev.workbench.persistence.repository;

import dev.workbench.domain.MemoryCategory;
import dev.workbench.domain.MemoryStatus;
import dev.workbench.persistence.entity.MemoryRecordEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 项目记忆按工作区读模型，以及带状态和版本前置条件的更新、软删除。 */
public interface MemoryRecordRepository extends JpaRepository<MemoryRecordEntity, String> {
  List<MemoryRecordEntity> findByWorkspaceIdAndStatusOrderByUpdatedAtDescIdAsc(
      String workspaceId, MemoryStatus status);

  List<MemoryRecordEntity> findByWorkspaceIdOrderByUpdatedAtDescIdAsc(String workspaceId);

  List<MemoryRecordEntity> findByWorkspaceIdAndStatusNotOrderByUpdatedAtDescIdAsc(
      String workspaceId, MemoryStatus status);

  boolean existsBySourceRunId(String sourceRunId);

  Optional<MemoryRecordEntity> findByIdAndWorkspaceIdAndStatus(
      String id, String workspaceId, MemoryStatus status);

  boolean existsByIdAndWorkspaceId(String id, String workspaceId);

  long countByWorkspaceIdAndStatus(String workspaceId, MemoryStatus status);

  @Transactional(propagation = Propagation.MANDATORY)
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      "update MemoryRecordEntity m set m.category = :category, m.content = :content,"
          + " m.version = m.version + 1, m.updatedAt = :updatedAt"
          + " where m.id = :id and m.workspaceId = :workspaceId and m.status = :active"
          + " and m.version = :version")
  int updateIfActive(
      @Param("id") String id,
      @Param("workspaceId") String workspaceId,
      @Param("category") MemoryCategory category,
      @Param("content") String content,
      @Param("updatedAt") String updatedAt,
      @Param("active") MemoryStatus active,
      @Param("version") int version);

  @Transactional(propagation = Propagation.MANDATORY)
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      "update MemoryRecordEntity m set m.status = :next, m.version = m.version + 1,"
          + " m.updatedAt = :updatedAt"
          + " where m.id = :id and m.workspaceId = :workspaceId and m.status = :current"
          + " and m.version = :version")
  int moveStatus(
      @Param("id") String id,
      @Param("workspaceId") String workspaceId,
      @Param("updatedAt") String updatedAt,
      @Param("next") MemoryStatus next,
      @Param("current") MemoryStatus current,
      @Param("version") int version);

  @Transactional(propagation = Propagation.MANDATORY)
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      "update MemoryRecordEntity m set m.hitCount = m.hitCount + 1, m.lastHitAt = :hitAt"
          + " where m.id = :id and m.status = :active")
  int recordHit(
      @Param("id") String id,
      @Param("hitAt") String hitAt,
      @Param("active") MemoryStatus active);
}
