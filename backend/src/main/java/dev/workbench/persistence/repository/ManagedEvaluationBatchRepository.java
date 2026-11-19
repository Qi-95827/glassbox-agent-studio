package dev.workbench.persistence.repository;

import dev.workbench.persistence.entity.ManagedEvaluationBatchEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ManagedEvaluationBatchRepository
    extends JpaRepository<ManagedEvaluationBatchEntity, String> {
  List<ManagedEvaluationBatchEntity> findAllByOrderByCreatedAtDesc();
}
