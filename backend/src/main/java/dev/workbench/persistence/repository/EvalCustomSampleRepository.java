package dev.workbench.persistence.repository;

import dev.workbench.persistence.entity.EvalCustomSampleEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** 自定义样本仓储。 */
public interface EvalCustomSampleRepository extends JpaRepository<EvalCustomSampleEntity, String> {
  List<EvalCustomSampleEntity> findByDatasetIdOrderBySampleKeyAsc(String datasetId);

  Optional<EvalCustomSampleEntity> findByDatasetIdAndSampleKey(String datasetId, String sampleKey);

  long countByDatasetId(String datasetId);
}
