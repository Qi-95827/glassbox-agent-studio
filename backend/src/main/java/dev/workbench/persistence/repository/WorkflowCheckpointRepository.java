package dev.workbench.persistence.repository;

import dev.workbench.persistence.entity.WorkflowCheckpointEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** 动态工作流检查点的常规读写，以及按计划和格式版本验证归属。 */
public interface WorkflowCheckpointRepository
    extends JpaRepository<WorkflowCheckpointEntity, String> {

  List<WorkflowCheckpointData> findByPlanIdOrderByCompletedNodesAsc(String planId);

  Optional<WorkflowCheckpointData> findByIdAndPlanIdAndVersion(
      String id, String planId, int version);

  boolean existsByPlanIdAndCompletedNodes(String planId, int completedNodes);
}