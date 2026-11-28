package dev.workbench.persistence.repository;

import dev.workbench.persistence.entity.ModelCallPayloadEntity;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ModelCallPayloadRepository extends JpaRepository<ModelCallPayloadEntity, String> {
  List<ModelCallPayloadEntity> findByRunId(String runId);

  /** 单节点下推：只取指定运行内、指定调用 ID 的载荷，绝不连带同运行其他节点的 64KB 级正文。 */
  List<ModelCallPayloadEntity> findByRunIdAndModelCallIdIn(
      String runId, Collection<String> modelCallIds);
}
