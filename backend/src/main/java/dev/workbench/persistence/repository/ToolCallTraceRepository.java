package dev.workbench.persistence.repository;

import dev.workbench.persistence.entity.ToolCallTraceEntity;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ToolCallTraceRepository extends JpaRepository<ToolCallTraceEntity, String> {
  List<ToolCallTraceEntity> findByRunId(String runId);

  /** 单节点下推：只取指定运行内、指定工具调用 ID 的轨迹。 */
  List<ToolCallTraceEntity> findByRunIdAndToolCallIdIn(
      String runId, Collection<String> toolCallIds);
}
