package dev.workbench.persistence.repository;

import dev.workbench.persistence.entity.RecoveryLeaseEntity;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RecoveryLeaseRepository extends JpaRepository<RecoveryLeaseEntity, String> {
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select l from RecoveryLeaseEntity l where l.runId = :runId")
  Optional<RecoveryLeaseEntity> lockByRunId(@Param("runId") String runId);
}
