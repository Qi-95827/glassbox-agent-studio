package dev.workbench.persistence.repository;

import dev.workbench.persistence.entity.EvalLabRunEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Eval Lab V2 批次仓储；列表按创建时间倒序。 */
public interface EvalLabRunRepository extends JpaRepository<EvalLabRunEntity, String> {
  List<EvalLabRunEntity> findByOwnerScopeKeyOrderByCreatedAtDesc(String ownerScopeKey);

  List<EvalLabRunEntity> findAllByOrderByCreatedAtDesc();
}
