package dev.workbench.persistence.repository;

import dev.workbench.persistence.entity.EvalCustomDatasetEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** 自定义评测集仓储；所有查询都带 owner 条件。 */
public interface EvalCustomDatasetRepository extends JpaRepository<EvalCustomDatasetEntity, String> {
  List<EvalCustomDatasetEntity> findByOwnerScopeKeyOrderByUpdatedAtDesc(String ownerScopeKey);

  Optional<EvalCustomDatasetEntity> findByIdAndOwnerScopeKey(String id, String ownerScopeKey);

  boolean existsByOwnerScopeKeyAndCode(String ownerScopeKey, String code);
}
