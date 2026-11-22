package dev.workbench.persistence.repository;

import dev.workbench.framework.tool.ToolApprovalStatus;
import dev.workbench.persistence.entity.ToolApprovalRequestEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ToolApprovalRequestRepository
    extends JpaRepository<ToolApprovalRequestEntity, String> {
  List<ToolApprovalRequestEntity> findByRunIdOrderByRequestedAtDesc(String runId);

  List<ToolApprovalRequestEntity> findByRunIdAndStatusOrderByRequestedAtDesc(
      String runId, ToolApprovalStatus status);

  Optional<ToolApprovalRequestEntity> findByIdAndRunId(String id, String runId);

  Optional<ToolApprovalRequestEntity> findFirstByRunIdAndNodeIdAndToolNameAndStatusOrderByRequestedAtDesc(
      String runId, String nodeId, String toolName, ToolApprovalStatus status);
}
