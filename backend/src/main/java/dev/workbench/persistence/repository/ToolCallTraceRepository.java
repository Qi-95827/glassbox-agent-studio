package dev.workbench.persistence.repository;

import dev.workbench.persistence.entity.ToolCallTraceEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ToolCallTraceRepository extends JpaRepository<ToolCallTraceEntity, String> {
  List<ToolCallTraceEntity> findByRunId(String runId);
}
