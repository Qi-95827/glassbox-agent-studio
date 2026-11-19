package dev.workbench.persistence.repository;

import dev.workbench.persistence.entity.MemoryHistoryEntity;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 项目记忆历史版本；工作区归属先由 memory_records 校验，再按版本倒序游标读取。 */
public interface MemoryHistoryRepository
    extends JpaRepository<MemoryHistoryEntity, MemoryHistoryEntity.Id> {

  @Query(
      "select h from MemoryHistoryEntity h where h.id.memoryId = :memoryId"
          + " order by h.id.version desc")
  List<MemoryHistoryEntity> findNewest(@Param("memoryId") String memoryId, Pageable pageable);

  @Query(
      "select h from MemoryHistoryEntity h where h.id.memoryId = :memoryId"
          + " and h.id.version < :beforeVersion order by h.id.version desc")
  List<MemoryHistoryEntity> findBefore(
      @Param("memoryId") String memoryId,
      @Param("beforeVersion") int beforeVersion,
      Pageable pageable);
}
