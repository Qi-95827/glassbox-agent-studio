package dev.workbench.persistence.repository;

import dev.workbench.persistence.entity.RunForkEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** 分支运行来源查询：按运行查来源，按父运行查所有分支。 */
public interface RunForkRepository extends JpaRepository<RunForkEntity, String> {
  Optional<RunForkEntity> findByRunId(String runId);

  List<RunForkEntity> findByParentRunIdOrderByCreatedAt(String parentRunId);
}
