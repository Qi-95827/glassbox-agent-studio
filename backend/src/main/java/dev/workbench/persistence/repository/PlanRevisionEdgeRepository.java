package dev.workbench.persistence.repository;

import dev.workbench.persistence.entity.PlanRevisionEdgeEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** 反思回边按运行读取；同一审查节点最多一条回边。 */
public interface PlanRevisionEdgeRepository
    extends JpaRepository<PlanRevisionEdgeEntity, String> {
  List<PlanRevisionEdgeEntity> findByRunIdOrderByReviewerNodeIdAsc(String runId);

  Optional<PlanRevisionEdgeEntity> findByRunIdAndReviewerNodeId(String runId, String reviewerNodeId);
}
