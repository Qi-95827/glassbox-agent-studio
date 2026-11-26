package dev.workbench.persistence.repository;

import dev.workbench.persistence.entity.ExternalTraceRunEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** 外部 Trace 运行：一律按当前账号的归属键过滤。 */
public interface ExternalTraceRunRepository extends JpaRepository<ExternalTraceRunEntity, String> {
  Optional<ExternalTraceRunEntity> findByIdAndOwnerScopeKey(String id, String ownerScopeKey);

  List<ExternalTraceRunEntity> findByOwnerScopeKeyOrderByCreatedAtDesc(String ownerScopeKey);

  boolean existsByOwnerScopeKeyAndName(String ownerScopeKey, String name);
}
