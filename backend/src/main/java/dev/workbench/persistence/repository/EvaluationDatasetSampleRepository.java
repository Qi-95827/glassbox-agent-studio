package dev.workbench.persistence.repository;

import dev.workbench.persistence.entity.EvaluationDatasetSampleEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EvaluationDatasetSampleRepository
    extends JpaRepository<EvaluationDatasetSampleEntity, String> {
  List<EvaluationDatasetSampleEntity> findByDatasetKeyOrderByCodeAsc(String datasetKey);

  Optional<EvaluationDatasetSampleEntity> findByDatasetKeyAndCode(String datasetKey, String code);
}
