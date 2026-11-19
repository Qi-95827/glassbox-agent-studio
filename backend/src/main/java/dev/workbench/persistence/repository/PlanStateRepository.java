package dev.workbench.persistence.repository;

import jakarta.persistence.EntityManager;

import dev.workbench.domain.AgentRole;
import dev.workbench.framework.workflow.plan.PlanNodeKind;
import dev.workbench.framework.workflow.plan.PlanNodeStatus;
import java.util.List;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** 持久化的节点与依赖，以及带并发前置条件的节点状态流转。*/
@Repository
@Transactional(readOnly = true)
public class PlanStateRepository {
  private static final List<PlanNodeStatus> READY_PREREQUISITES =
      List.of(PlanNodeStatus.COMPLETED, PlanNodeStatus.REUSED);

  // 认领时重验当前依赖，防止在读取就绪列表后启动已经失去前置条件的步骤。
private static final String NO_BLOCKING_EDGE =
      """
      and not exists (
        select d.id.nodeId from PlanNodeDependencyEntity d
        left join PlanNodeEntity p on p.id = d.id.prerequisiteId
        where d.id.nodeId = n.id
          and (d.id.runId <> n.runId or p.id is null or p.runId <> n.runId or p.status not in :ready)
      )
      """;
  private final EntityManager sessions;

  public PlanStateRepository(EntityManager sessions) {
    this.sessions = sessions;
  }

  public List<NodeRow> findNodes(String runId) {
    return sessions
        .createQuery(
            """
            select n.id, n.runId, n.sequenceNo, n.roleCode, n.prerequisiteRole, n.status, n.agentId, n.sourceAgentId,
              n.startedAt, n.finishedAt, n.definitionId, n.definitionVersion, n.definitionSha256, n.planVersionId, n.nodeKey, n.nodeLabel,
              n.phaseKey, n.permissionRole, n.workspaceRead, n.workspaceWrite, n.maxNodeModelCalls, n.maxNodeToolCalls, n.nodeKind, n.checkId, n.checkVersion
            from PlanNodeEntity n where n.runId = :runId order by n.sequenceNo
            """,
            NodeRow.class)
        .setParameter("runId", runId)
        .getResultList();
  }

  public List<EdgeRow> findEdges(String runId) {
    return sessions
        .createQuery(
            """
            select d.id.runId, d.id.nodeId, d.id.prerequisiteId
            from PlanNodeDependencyEntity d join PlanNodeEntity n on n.id = d.id.nodeId
            where n.runId = :runId order by n.sequenceNo, d.id.prerequisiteId
            """,
            EdgeRow.class)
        .setParameter("runId", runId)
        .getResultList();
  }

  @Transactional
  public boolean claimCheck(String runId, String nodeId, String time) {
    return sessions
            .createQuery(
                """
                update PlanNodeEntity n set n.status = :running, n.startedAt = :time
                where n.id = :id and n.runId = :runId and n.status = :pending
                  and n.agentId is null and n.nodeKind = :check
                """
                    + NO_BLOCKING_EDGE)
            .setParameter("runId", runId)
            .setParameter("id", nodeId)
            .setParameter("time", time)
            .setParameter("pending", PlanNodeStatus.PENDING)
            .setParameter("running", PlanNodeStatus.RUNNING)
            .setParameter("check", PlanNodeKind.SYSTEM_CHECK)
            .setParameter("ready", READY_PREREQUISITES)
            .executeUpdate()
        == 1;
  }

  @Transactional
  public boolean claimAgent(String runId, String nodeId, String agentId, String time) {
    return sessions
            .createQuery(
                """
                update PlanNodeEntity n set n.status = :running, n.agentId = :agentId, n.startedAt = :time
                where n.id = :id and n.runId = :runId and n.status = :pending
                  and (n.prerequisiteRole is null or exists (
                    select d.id.nodeId from PlanNodeDependencyEntity d
                    join PlanNodeEntity p on p.id = d.id.prerequisiteId
                    where d.id.nodeId = n.id and d.id.runId = n.runId and p.runId = n.runId
                      and p.roleCode = n.prerequisiteRole and p.status in :ready
                  ))
                """
                    + NO_BLOCKING_EDGE)
            .setParameter("runId", runId)
            .setParameter("id", nodeId)
            .setParameter("agentId", agentId)
            .setParameter("time", time)
            .setParameter("pending", PlanNodeStatus.PENDING)
            .setParameter("running", PlanNodeStatus.RUNNING)
            .setParameter("ready", READY_PREREQUISITES)
            .executeUpdate()
        == 1;
  }

  @Transactional
  public boolean finishCheck(String runId, String nodeId, PlanNodeStatus status, String time) {
    return sessions
            .createQuery(
                """
                update PlanNodeEntity n set n.status = :status, n.finishedAt = :time
                where n.id = :id and n.runId = :runId and n.status = :running
                  and n.agentId is null and n.nodeKind = :check
                """)
            .setParameter("runId", runId)
            .setParameter("id", nodeId)
            .setParameter("status", status)
            .setParameter("time", time)
            .setParameter("running", PlanNodeStatus.RUNNING)
            .setParameter("check", PlanNodeKind.SYSTEM_CHECK)
            .executeUpdate()
        == 1;
  }

  @Transactional
  public boolean finishAgent(String agentId, PlanNodeStatus status, String time) {
    return sessions
            .createQuery(
                """
                update PlanNodeEntity n set n.status = :status, n.finishedAt = :time
                where n.agentId = :agentId and n.status = :running
                """)
            .setParameter("agentId", agentId)
            .setParameter("status", status)
            .setParameter("time", time)
            .setParameter("running", PlanNodeStatus.RUNNING)
            .executeUpdate()
        == 1;
  }

  @Transactional
  public boolean reuseNode(String runId, String key, String sourceAgentId, String time) {
    return markReused("and n.nodeKey = :node", runId, key, sourceAgentId, time);
  }

  @Transactional
  public boolean reuseAnalysis(String runId, String sourceAgentId, String time) {
    return markReused(
        "and n.roleCode = :node", runId, AgentRole.ANALYST.name(), sourceAgentId, time);
  }

  private boolean markReused(
      String target, String runId, String node, String sourceAgentId, String time) {
    return sessions
            .createQuery(
                """
                update PlanNodeEntity n set n.status = :reused, n.sourceAgentId = :source, n.finishedAt = :time
                where n.runId = :runId and n.status = :pending
                """
                    + target)
            .setParameter("runId", runId)
            .setParameter("node", node)
            .setParameter("source", sourceAgentId)
            .setParameter("time", time)
            .setParameter("pending", PlanNodeStatus.PENDING)
            .setParameter("reused", PlanNodeStatus.REUSED)
            .executeUpdate()
        == 1;
  }

  @Transactional
  public boolean requeueFailed(String runId, String nodeId, String agentId) {
    return sessions
            .createQuery(
                """
                update PlanNodeEntity n set n.status = :pending, n.agentId = null,
                  n.startedAt = null, n.finishedAt = null
                where n.runId = :runId and n.id = :id and n.agentId = :agentId
                  and n.status = :failed and n.planVersionId is not null
                """)
            .setParameter("runId", runId)
            .setParameter("id", nodeId)
            .setParameter("agentId", agentId)
            .setParameter("pending", PlanNodeStatus.PENDING)
            .setParameter("failed", PlanNodeStatus.FAILED)
            .executeUpdate()
        == 1;
  }

  @Transactional
  public void settle(String runId, String time) {
    settleStatus(runId, PlanNodeStatus.RUNNING, PlanNodeStatus.INTERRUPTED, time);
    settleStatus(runId, PlanNodeStatus.PENDING, PlanNodeStatus.SKIPPED, time);
  }

  private void settleStatus(
      String runId, PlanNodeStatus current, PlanNodeStatus next, String time) {
    sessions
        .createQuery(
            """
            update PlanNodeEntity n set n.status = :next, n.finishedAt = :time
            where n.runId = :runId and n.status = :current
            """)
        .setParameter("runId", runId)
        .setParameter("current", current)
        .setParameter("next", next)
        .setParameter("time", time)
        .executeUpdate();
  }

  public record EdgeRow(String runId, String nodeId, String prerequisiteId) {}

  public record NodeRow(
      String id,
      String runId,
      Integer sequenceNo,
      String roleCode,
      String prerequisiteRole,
      PlanNodeStatus status,
      String agentId,
      String sourceAgentId,
      String startedAt,
      String finishedAt,
      String definitionId,
      Integer definitionVersion,
      String definitionSha256,
      String planVersionId,
      String nodeKey,
      String nodeLabel,
      String phaseKey,
      String permissionRole,
      Boolean workspaceRead,
      Boolean workspaceWrite,
      Integer maxNodeModelCalls,
      Integer maxNodeToolCalls,
      PlanNodeKind nodeKind,
      String checkId,
      Integer checkVersion) {}
}