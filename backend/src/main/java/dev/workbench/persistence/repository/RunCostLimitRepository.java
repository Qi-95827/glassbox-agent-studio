package dev.workbench.persistence.repository;

import dev.workbench.persistence.entity.RunCostLimitEntity;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RunCostLimitRepository extends JpaRepository<RunCostLimitEntity, String> {
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select c from RunCostLimitEntity c where c.runId = :runId")
  Optional<RunCostLimitEntity> lockByRunId(@Param("runId") String runId);
}
