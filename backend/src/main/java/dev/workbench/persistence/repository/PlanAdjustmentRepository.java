package dev.workbench.persistence.repository;

import dev.workbench.persistence.entity.PlanAdjustmentEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlanAdjustmentRepository extends JpaRepository<PlanAdjustmentEntity, String> {
  List<PlanAdjustmentEntity> findByRunIdOrderByVersionAsc(String runId);

  Optional<PlanAdjustmentEntity> findFirstByRunIdOrderByVersionDesc(String runId);

  Optional<PlanAdjustmentEntity> findByIdAndRunId(String id, String runId);
}
