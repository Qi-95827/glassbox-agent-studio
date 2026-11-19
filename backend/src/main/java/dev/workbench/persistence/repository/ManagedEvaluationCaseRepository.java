package dev.workbench.persistence.repository;

import dev.workbench.persistence.entity.ManagedEvaluationCaseEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ManagedEvaluationCaseRepository
    extends JpaRepository<ManagedEvaluationCaseEntity, String> {
  List<ManagedEvaluationCaseEntity> findByBatchIdOrderBySampleCodeAsc(String batchId);
}
