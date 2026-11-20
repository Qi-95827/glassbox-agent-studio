package dev.workbench.persistence.repository;

import dev.workbench.persistence.entity.PlanNodeSpecEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** 节点规格按运行读取；写入与计划节点在同一事务完成。 */
public interface PlanNodeSpecRepository extends JpaRepository<PlanNodeSpecEntity, String> {
  List<PlanNodeSpecEntity> findByRunId(String runId);

  List<PlanNodeSpecEntity> findByRunIdOrderByNodeKey(String runId);
}
