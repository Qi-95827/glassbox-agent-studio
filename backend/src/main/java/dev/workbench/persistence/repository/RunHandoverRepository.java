package dev.workbench.persistence.repository;

import dev.workbench.domain.InvocationStatus;
import dev.workbench.framework.workflow.check.SystemCheckStatus;
import dev.workbench.persistence.entity.RunHandoverEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 人工接手历史和恢复前置检查。
 *
 * <p>在途动作统计跨模型调用、工具调用和系统检查，使用 JPQL 聚合，避免逐表读取。
 */
public interface RunHandoverRepository extends JpaRepository<RunHandoverEntity, String> {
  Optional<RunHandoverEntity> findFirstByRunIdOrderByTakenAtDescIdDesc(String runId);

  Optional<RunHandoverEntity> findFirstByRunIdAndStatusOrderByTakenAtDescIdDesc(
      String runId, String status);

  boolean existsByRunIdAndStatusAndEvidenceInvalidatedTrue(String runId, String status);

  @Query(
      "select count(m) from ModelInvocationEntity m"
          + " where m.runId = :runId and m.status = :invocationStatus")
  long countRunningModelInvocations(
      @Param("runId") String runId, @Param("invocationStatus") InvocationStatus invocationStatus);

  @Query(
      "select count(t) from ToolInvocationEntity t"
          + " where t.runId = :runId and t.status = :invocationStatus")
  long countRunningToolInvocations(
      @Param("runId") String runId, @Param("invocationStatus") InvocationStatus invocationStatus);

  @Query(
      "select count(c) from WorkflowCheckEntity c"
          + " where c.status = :checkStatus"
          + " and c.nodeId in (select p.id from PlanNodeEntity p where p.runId = :runId)")
  long countRunningChecks(
      @Param("runId") String runId, @Param("checkStatus") SystemCheckStatus checkStatus);
}
