package dev.workbench.persistence.repository;

import jakarta.persistence.EntityManager;

import dev.workbench.domain.InvocationStatus;
import dev.workbench.domain.StopCause;
import dev.workbench.domain.ToolKind;
import dev.workbench.framework.workflow.check.SystemCheckStatus;
import dev.workbench.framework.workflow.plan.PlanNodeKind;
import dev.workbench.framework.workflow.plan.PlanNodeStatus;
import java.util.List;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** 恢复边界的已结算调用和检查证据投影，避免业务层接触数据库字段。*/
@Repository
@Transactional(readOnly = true)
public class WorkflowRecoveryEvidenceRepository {
  private final EntityManager sessions;

  public WorkflowRecoveryEvidenceRepository(EntityManager sessions) {
    this.sessions = sessions;
  }

  public boolean hasRateLimitAttempt(String runId) {
    return sessions
            .createQuery(
                """
                select a.id from WorkflowAttemptEntity a join PlanNodeEntity n on n.id = a.nodeId
                where n.runId = :runId and a.stopCause = :cause
                """,
                String.class)
            .setParameter("runId", runId)
            .setParameter("cause", StopCause.MODEL_RATE_LIMIT)
            .setMaxResults(1)
            .getResultList().stream().findFirst().orElse(null)
        != null;
  }

  public List<SourceRow> findCompletedSources(String runId, String planId) {
    return sessions
        .createQuery(
            """
            select n.nodeKey, n.id, a.id, i.id, i.result
            from PlanNodeEntity n
            join WorkflowAttemptEntity a on a.nodeId = n.id and a.agentId = n.agentId
            join AgentInvocationEntity i on i.id = a.agentId and i.runId = n.runId
            where n.runId = :runId and n.planVersionId = :planId
                and n.status = :nodeStatus and i.status = :agentStatus
                and i.definitionId = n.definitionId and i.definitionVersion = n.definitionVersion
                and i.definitionSha256 = n.definitionSha256 and i.result is not null
            order by n.sequenceNo
            """,
            SourceRow.class)
        .setParameter("runId", runId)
        .setParameter("planId", planId)
        .setParameter("nodeStatus", PlanNodeStatus.COMPLETED)
        .setParameter("agentStatus", InvocationStatus.COMPLETED)
        .getResultList();
  }

  public List<UsageRow> findCompletedModelUsage(String runId) {
    return sessions
        .createQuery(
            """
            select m.id, m.agentId, m.modelName, m.inputTokens, m.outputTokens, m.durationMs
            from ModelInvocationEntity m
            join AgentInvocationEntity a on a.id = m.agentId and a.runId = m.runId
            where m.runId = :runId and m.status = :completed and a.status = :completed
            order by m.id
            """,
            UsageRow.class)
        .setParameter("runId", runId)
        .setParameter("completed", InvocationStatus.COMPLETED)
        .getResultList();
  }

  public long countAgents(String runId) {
    return sessions
        .createQuery(
            "select count(*) from AgentInvocationEntity a where a.runId = :runId", Long.class)
        .setParameter("runId", runId)
        .getSingleResult();
  }

  public long countModels(String runId) {
    return sessions
        .createQuery(
            "select count(*) from ModelInvocationEntity m where m.runId = :runId", Long.class)
        .setParameter("runId", runId)
        .getSingleResult();
  }

  public boolean hasToolCalls(String runId) {
    return sessions
            .createQuery(
                "select t.id from ToolInvocationEntity t where t.runId = :runId", String.class)
            .setParameter("runId", runId)
            .setMaxResults(1)
            .getResultList().stream().findFirst().orElse(null)
        != null;
  }

  public boolean hasRunningToolCall(String runId) {
    return sessions
            .createQuery(
                """
                select t.id from ToolInvocationEntity t
                join AgentInvocationEntity a on a.id = t.agentId and a.runId = t.runId
                where t.runId = :runId and t.status = :running
                """,
                String.class)
            .setParameter("runId", runId)
            .setParameter("running", InvocationStatus.RUNNING)
            .setMaxResults(1)
            .getResultList().stream().findFirst().orElse(null)
        != null;
  }

  /** 按实际版本关联工具调用与授权，读取完整文件版本证据。*/
  public List<FileEvidenceRow> findCompletedFileEvidence(String runId) {
    return sessions
        .createQuery(
            """
            select i.id, i.agentId, g.id.toolKind, i.resourceVersion
            from ToolInvocationEntity i
            join AgentToolGrantEntity g on g.id.agentId = i.agentId and g.toolVersion = i.toolVersion
            where i.runId = :runId and i.status = :completed and g.id.toolKind in :toolKinds
            order by i.createdAt, i.id
            """,
            FileEvidenceRow.class)
        .setParameter("runId", runId)
        .setParameter("completed", InvocationStatus.COMPLETED)
        .setParameter(
            "toolKinds", List.of(ToolKind.WORKSPACE_READ.name(), ToolKind.WORKSPACE_SEARCH.name()))
        .getResultList();
  }

  public boolean hasRunningCheck(String runId) {
    return sessions
            .createQuery(
                """
                select c.id from WorkflowCheckEntity c join PlanNodeEntity n on n.id = c.nodeId
                where n.runId = :runId and c.status = :running
                """,
                String.class)
            .setParameter("runId", runId)
            .setParameter("running", SystemCheckStatus.RUNNING)
            .setMaxResults(1)
            .getResultList().stream().findFirst().orElse(null)
        != null;
  }

  public List<CheckRow> findLatestCompletedNodeChecks(String runId) {
    return sessions
        .createQuery(
            """
            select n.nodeKey, c.id, c.status, c.inputsSha256, c.externalEvidenceId
            from PlanNodeEntity n
            join WorkflowCheckEntity c on c.nodeId = n.id
                and c.attemptNo = (select max(x.attemptNo) from WorkflowCheckEntity x where x.nodeId = n.id)
            where n.runId = :runId and n.nodeKind = :checkKind and n.status = :completed
            order by n.sequenceNo
            """,
            CheckRow.class)
        .setParameter("runId", runId)
        .setParameter("checkKind", PlanNodeKind.SYSTEM_CHECK)
        .setParameter("completed", PlanNodeStatus.COMPLETED)
        .getResultList();
  }

  public record SourceRow(
      String nodeKey, String nodeId, String attemptId, String agentId, String result) {}

  public record UsageRow(
      String id, String agentId, String model, Integer input, Integer output, Long durationMs) {}

  public record FileEvidenceRow(
      String id, String agentId, String toolKind, String resourceVersion) {
    public ToolKind tool() {
      return ToolKind.valueOf(toolKind);
    }
  }

  public record CheckRow(
      String nodeKey,
      String id,
      SystemCheckStatus status,
      String inputsSha256,
      String externalEvidenceId) {}
}