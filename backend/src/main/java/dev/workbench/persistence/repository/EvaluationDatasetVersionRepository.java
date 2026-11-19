package dev.workbench.persistence.repository;

import dev.workbench.persistence.entity.EvaluationDatasetVersionEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EvaluationDatasetVersionRepository
    extends JpaRepository<EvaluationDatasetVersionEntity, String> {
  List<EvaluationDatasetVersionEntity> findByDatasetKeyOrderByVersionDesc(String datasetKey);

  Optional<EvaluationDatasetVersionEntity> findFirstByDatasetKeyOrderByVersionDesc(String datasetKey);

  Optional<EvaluationDatasetVersionEntity> findByIdAndDatasetKey(String id, String datasetKey);
}
