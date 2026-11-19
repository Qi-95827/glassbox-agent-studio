package dev.workbench.persistence.repository;

import dev.workbench.persistence.entity.RunMemoryReferenceEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 运行记忆引用按装配顺序读取，保留当时的版本和类别。 */
public interface RunMemoryReferenceRepository
    extends JpaRepository<RunMemoryReferenceEntity, RunMemoryReferenceEntity.Id> {

  @Query(
      "select r from RunMemoryReferenceEntity r where r.id.runId = :runId"
          + " order by r.id.sequenceNo")
  List<RunMemoryReferenceEntity> findByRunId(@Param("runId") String runId);
}
