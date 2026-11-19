package dev.workbench.persistence.repository;

import jakarta.persistence.EntityManager;

import dev.workbench.domain.InvocationStatus;
import dev.workbench.domain.StopCause;
import dev.workbench.domain.ToolKind;
import dev.workbench.framework.workflow.check.SystemCheckRef;
import dev.workbench.framework.workflow.check.SystemCheckResult;
import dev.workbench.framework.workflow.check.SystemCheckStatus;
import dev.workbench.framework.workflow.plan.PlanNodeKind;
import dev.workbench.framework.workflow.plan.PlanNodeStatus;
import dev.workbench.framework.workflow.plan.WorkflowHandoff.ToolEvidence;
import dev.workbench.persistence.entity.WorkflowAttemptEntity;
import dev.workbench.persistence.entity.WorkflowCheckEntity;
import dev.workbench.persistence.entity.WorkflowCheckInputEntity;
import dev.workbench.persistence.entity.WorkflowHandoffCheckEntity;
import dev.workbench.persistence.entity.WorkflowHandoffEntity;
import dev.workbench.persistence.entity.WorkflowHandoffReadEvidenceEntity;
import dev.workbench.persistence.entity.WorkflowHandoffWriteEvidenceEntity;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 动态尝试、系统检查、依赖结果和接收证据的数据库操作。*/
@Repository
@Transactional(readOnly = true)
public class WorkflowExecutionRepository {
  private static final int HANDOFF_FORMAT_VERSION = 1;
  private final EntityManager sessions;

  public WorkflowExecutionRepository(EntityManager sessions) {
    this.sessions = sessions;
  }

  public int nextAttemptNumber(String nodeId) {
    return sessions
        .createQuery(
            "select coalesce(max(a.attemptNo), 0) + 1 from WorkflowAttemptEntity a where a.nodeId ="
                + " :nodeId",
            Integer.class)
        .setParameter("nodeId", nodeId)
        .getSingleResult();
  }

  public int nextCheckNumber(String nodeId) {
    return sessions
        .createQuery(
            "select coalesce(max(c.attemptNo), 0) + 1 from WorkflowCheckEntity c where c.nodeId ="
                + " :nodeId",
            Integer.class)
        .setParameter("nodeId", nodeId)
        .getSingleResult();
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void insertAttempt(String id, String nodeId, int number, String agentId) {
    sessions.persist(new WorkflowAttemptEntity(id, nodeId, number, agentId));
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void insertCheck(
      String id,
      String nodeId,
      int number,
      SystemCheckRef reference,
      String fingerprint,
      String time) {
    sessions.persist(
        new WorkflowCheckEntity(
            id, nodeId, number, reference.id(), reference.version(), fingerprint, time));
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void insertCheckInput(
      String checkId,
      String dependencyId,
      PlanNodeKind kind,
      String sourceAttemptId,
      String sourceCheckId,
      String sourceRunId,
      String sha256) {
    sessions.persist(
        new WorkflowCheckInputEntity(
            checkId,
            dependencyId,
            kind.name(),
            sourceAttemptId,
            sourceCheckId,
            sourceRunId,
            sha256));
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void insertCheckReceipt(String receiver, String dependency, String checkId, String time) {
    sessions.persist(new WorkflowHandoffCheckEntity(receiver, dependency, checkId, time));
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void insertAgentReceipt(String receiver, String dependency, String sender, String time) {
    sessions.persist(
        new WorkflowHandoffEntity(receiver, dependency, sender, HANDOFF_FORMAT_VERSION, time));
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void insertToolEvidence(String receiver, String dependency, ToolEvidence evidence) {
    String toolId = evidence.toolCallId().toString();
    if (evidence.tool() == ToolKind.WORKSPACE_WRITE) {
      sessions.persist(
          new WorkflowHandoffWriteEvidenceEntity(
              receiver, dependency, toolId, evidence.version(), evidence.resourceVersion()));
    } else {
      sessions.persist(
          new WorkflowHandoffReadEvidenceEntity(
              receiver,
              dependency,
              toolId,
              evidence.tool().name(),
              evidence.version(),
              evidence.resourceVersion()));
    }
  }

  public Optional<CheckSettlement> findCheckSettlement(String runId, String checkId) {
    return sessions
        .createQuery(
            """
            select c.nodeId, c.status, n.nodeLabel
            from WorkflowCheckEntity c join PlanNodeEntity n on n.id = c.nodeId
            where c.id = :id and n.runId = :runId
            """,
            CheckSettlement.class)
        .setParameter("id", checkId)
        .setParameter("runId", runId)
        .getResultList().stream().findFirst();
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public boolean finishCheck(String checkId, SystemCheckResult result, String time) {
    return sessions
            .createQuery(
                """
                update WorkflowCheckEntity c set c.status = :status, c.summary = :summary,
                  c.evidence = :evidence, c.externalEvidenceId = :external, c.finishedAt = :time
                where c.id = :id and c.status = :running
                """)
            .setParameter("id", checkId)
            .setParameter("status", result.status())
            .setParameter("summary", result.summary())
            .setParameter("evidence", result.evidence())
            .setParameter("external", result.externalEvidenceId())
            .setParameter("time", time)
            .setParameter("running", SystemCheckStatus.RUNNING)
            .executeUpdate()
        == 1;
  }

  public AttemptIdentity findAttemptIdentity(String agentId) {
    return sessions
        .createQuery(
            "select a.id, a.attemptNo from WorkflowAttemptEntity a where a.agentId = :agentId",
            AttemptIdentity.class)
        .setParameter("agentId", agentId)
        .getSingleResult();
  }

  /** 没有其他失败或未结算模型调用时，只允许一次明确的限流拒绝。*/
  public boolean canRetryRateLimit(String runId, String agentId, String safeMessage) {
    var info =
        sessions
            .createQuery(
                """
                select coalesce(sum(case when m.status = :failed and m.errorMessage = :message then 1 else 0 end), 0),
                  coalesce(sum(case when m.status <> :completed and
                    (m.status <> :failed or m.errorMessage is distinct from :message) then 1 else 0 end), 0)
                from ModelInvocationEntity m where m.runId = :runId and m.agentId = :agentId
                """,
                RateLimitCounts.class)
            .setParameter("runId", runId)
            .setParameter("agentId", agentId)
            .setParameter("failed", InvocationStatus.FAILED)
            .setParameter("completed", InvocationStatus.COMPLETED)
            .setParameter("message", safeMessage)
            .getSingleResult();
    return info.rejected() == 1 && info.unresolved() == 0;
  }

  public String findExternalReference(String checkId) {
    var rows =
        sessions
            .createQuery(
                "select c.externalEvidenceId from WorkflowCheckEntity c where c.id = :id",
                String.class)
            .setParameter("id", checkId)
            .getResultList();
    return rows.isEmpty() ? null : rows.getFirst();
  }

  public Optional<ExternalEvidence> findExternalEvidence(String externalId) {
    return sessions
        .createQuery(
            """
            select b.bugId, b.workspaceId, b.workspaceFingerprint
            from BugCheckReferenceEntity b where b.id = :id
            """,
            ExternalEvidence.class)
        .setParameter("id", externalId)
        .getResultList().stream().findFirst();
  }

  public List<AgentResult> findAgentResults(String runId, String recipient) {
    return sessions
        .createQuery(
            """
            select n.id, n.nodeKey, n.nodeLabel, n.sequenceNo, p.id, p.runId,
              a.id, i.id, i.result, p.workspaceRead, p.workspaceWrite
            from PlanNodeEntity n join RunEntity r on r.id = n.runId
            join WorkflowAttemptEntity a on a.agentId = coalesce(n.agentId, n.sourceAgentId)
            join PlanNodeEntity p on p.id = a.nodeId and p.agentId = a.agentId
            join AgentInvocationEntity i on i.id = a.agentId and i.runId = p.runId
            where n.runId = :runId and n.nodeKind = :agentKind and p.status = :completed
              and i.status = :agentCompleted and i.result is not null and i.definitionId = n.definitionId
              and i.definitionVersion = n.definitionVersion and i.definitionSha256 = n.definitionSha256
              and ((n.status = :completed and n.id = p.id)
                or (n.status = :reused and p.runId = r.parentId and n.nodeKey = p.nodeKey))
              and (:recipient is null or exists (select d.id.nodeId from PlanNodeDependencyEntity d
                where d.id.runId = n.runId and d.id.nodeId = :recipient and d.id.prerequisiteId = n.id))
            order by n.sequenceNo
            """,
            AgentResult.class)
        .setParameter("runId", runId)
        .setParameter("recipient", recipient)
        .setParameter("agentKind", PlanNodeKind.AGENT)
        .setParameter("completed", PlanNodeStatus.COMPLETED)
        .setParameter("reused", PlanNodeStatus.REUSED)
        .setParameter("agentCompleted", InvocationStatus.COMPLETED)
        .getResultList();
  }

  public List<CheckResult> findCheckResults(String runId, String recipient) {
    return sessions
        .createQuery(
            """
            select n.id, n.nodeKey, n.nodeLabel, n.sequenceNo, n.runId, c.id, c.status, c.summary, c.evidence
            from PlanNodeEntity n join WorkflowCheckEntity c on c.nodeId = n.id
              and c.attemptNo = (select max(x.attemptNo) from WorkflowCheckEntity x where x.nodeId = n.id)
            where n.runId = :runId and n.nodeKind = :checkKind and n.status = :completed
              and c.status = :passed
              and (:recipient is null or exists (select d.id.nodeId from PlanNodeDependencyEntity d
                where d.id.runId = n.runId and d.id.nodeId = :recipient and d.id.prerequisiteId = n.id))
            order by n.sequenceNo
            """,
            CheckResult.class)
        .setParameter("runId", runId)
        .setParameter("recipient", recipient)
        .setParameter("checkKind", PlanNodeKind.SYSTEM_CHECK)
        .setParameter("completed", PlanNodeStatus.COMPLETED)
        .setParameter("passed", SystemCheckStatus.PASSED)
        .getResultList();
  }

  public List<ToolEvidence> findToolEvidence(
      String runId, String agentId, boolean writeAuthorized) {
    return sessions
        .createQuery(
            """
            select i.id, g.id.toolKind, i.toolVersion, i.resourceVersion
            from ToolInvocationEntity i
            join AgentToolGrantEntity g on g.id.agentId = i.agentId and g.toolVersion = i.toolVersion
            where i.runId = :runId and i.agentId = :agentId and i.status = :completed
              and ((g.id.toolKind = :read and i.toolName = :readName)
                or (g.id.toolKind = :search and i.toolName = :searchName)
                or (g.id.toolKind = :write and i.toolName = :writeName and :writeAllowed = true))
            order by i.createdAt, i.id
            """,
            ToolEvidenceRow.class)
        .setParameter("runId", runId)
        .setParameter("agentId", agentId)
        .setParameter("completed", InvocationStatus.COMPLETED)
        .setParameter("read", ToolKind.WORKSPACE_READ.name())
        .setParameter("search", ToolKind.WORKSPACE_SEARCH.name())
        .setParameter("write", ToolKind.WORKSPACE_WRITE.name())
        .setParameter("readName", ToolKind.WORKSPACE_READ.recordName())
        .setParameter("searchName", ToolKind.WORKSPACE_SEARCH.recordName())
        .setParameter("writeName", ToolKind.WORKSPACE_WRITE.recordName())
        .setParameter("writeAllowed", writeAuthorized)
        .getResultList()
        .stream()
        .map(ToolEvidenceRow::toEvidence)
        .toList();
  }

  public List<ToolEvidence> findAcceptedToolEvidence(String receiver, String dependency) {
    var reads =
        sessions
            .createQuery(
                """
                select e.id.toolId, e.toolKind, e.toolVersion, e.resourceVersion
                from WorkflowHandoffReadEvidenceEntity e
                where e.id.receiverAttemptId = :receiver and e.id.dependencyNodeId = :dependency
                """,
                ToolEvidenceRow.class)
            .setParameter("receiver", receiver)
            .setParameter("dependency", dependency)
            .getResultList();
    var writes =
        sessions
            .createQuery(
                """
                select e.id.toolId, e.toolVersion, e.resourceVersion
                from WorkflowHandoffWriteEvidenceEntity e
                where e.id.receiverAttemptId = :receiver and e.id.dependencyNodeId = :dependency
                """,
                WriteEvidenceRow.class)
            .setParameter("receiver", receiver)
            .setParameter("dependency", dependency)
            .getResultList();
    return Stream.concat(reads.stream(), writes.stream().map(WriteEvidenceRow::withKind))
        .sorted(Comparator.comparing(ToolEvidenceRow::toolId))
        .map(ToolEvidenceRow::toEvidence)
        .toList();
  }

  public List<AttemptView> findAttempts(String runId) {
    return sessions
        .createQuery(
            """
            select a.id, a.nodeId, a.attemptNo, a.agentId, i.status, i.createdAt, i.updatedAt, a.stopCause
            from WorkflowAttemptEntity a join AgentInvocationEntity i on i.id = a.agentId
            join PlanNodeEntity n on n.id = a.nodeId and n.runId = i.runId
            where n.runId = :runId order by n.sequenceNo, a.attemptNo
            """,
            AttemptView.class)
        .setParameter("runId", runId)
        .getResultList();
  }

  public List<CheckInputView> findCheckInputs(String runId) {
    return sessions
        .createQuery(
            """
            select i.id.checkId, i.id.dependencyNodeId, i.sourceKind, i.sourceAttemptId,
              i.sourceCheckId, i.sourceRunId, i.resultSha256
            from WorkflowCheckInputEntity i join WorkflowCheckEntity c on c.id = i.id.checkId
            join PlanNodeEntity n on n.id = c.nodeId join PlanNodeEntity d on d.id = i.id.dependencyNodeId
            where n.runId = :runId order by i.id.checkId, d.sequenceNo
            """,
            CheckInputView.class)
        .setParameter("runId", runId)
        .getResultList();
  }

  public List<CheckView> findChecks(String runId) {
    return sessions
        .createQuery(
            """
            select c.id, c.nodeId, n.nodeKey, c.attemptNo, c.checkId, c.checkVersion, c.status,
              c.summary, c.evidence, c.externalEvidenceId, c.inputsSha256, c.startedAt, c.finishedAt,
              b.workspaceFingerprint
            from WorkflowCheckEntity c join PlanNodeEntity n on n.id = c.nodeId
            left join BugCheckReferenceEntity b on b.id = c.externalEvidenceId
            where n.runId = :runId order by n.sequenceNo, c.attemptNo
            """,
            CheckView.class)
        .setParameter("runId", runId)
        .getResultList();
  }

  public List<HandoffView> findHandoffs(String runId) {
    return sessions
        .createQuery(
            """
            select h.schemaVersion, h.acceptedAt, s.id, s.nodeId, s.agentId,
              h.id.dependencyNodeId, r.id, r.nodeId, r.agentId
            from WorkflowHandoffEntity h join WorkflowAttemptEntity r on r.id = h.id.receiverAttemptId
            join WorkflowAttemptEntity s on s.id = h.senderAttemptId
            join PlanNodeEntity n on n.id = r.nodeId join PlanNodeEntity p on p.id = s.nodeId
            join PlanNodeEntity d on d.id = h.id.dependencyNodeId and d.runId = n.runId
            join PlanNodeDependencyEntity e on e.id.runId = n.runId and e.id.nodeId = n.id
              and e.id.prerequisiteId = d.id
            where n.runId = :runId and d.planVersionId = n.planVersionId
            order by n.sequenceNo, r.attemptNo, p.sequenceNo, s.attemptNo
            """,
            HandoffView.class)
        .setParameter("runId", runId)
        .getResultList();
  }

  public boolean isRunningAttempt(String runId, String agentId) {
    return sessions
            .createQuery(
                """
                select a.id from WorkflowAttemptEntity a
                join PlanNodeEntity n on n.id = a.nodeId and n.agentId = a.agentId
                where n.runId = :runId and a.agentId = :agentId
                  and n.planVersionId is not null and n.status = :running
                """,
                String.class)
            .setParameter("runId", runId)
            .setParameter("agentId", agentId)
            .setParameter("running", PlanNodeStatus.RUNNING)
            .getResultList().stream().findFirst().orElse(null)
        != null;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void interruptChecks(String runId, String time) {
    sessions
        .createQuery(
            """
            update WorkflowCheckEntity c set c.status = :interrupted,
              c.summary = coalesce(c.summary, :unknown), c.finishedAt = :time
            where c.status = :running and c.nodeId in (select n.id from PlanNodeEntity n where n.runId = :runId)
            """)
        .setParameter("runId", runId)
        .setParameter("time", time)
        .setParameter("unknown", "运行在检查结算前停止，检查结果未知。")
        .setParameter("running", SystemCheckStatus.RUNNING)
        .setParameter("interrupted", SystemCheckStatus.INTERRUPTED)
        .executeUpdate();
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public boolean recordStopCause(String agentId, StopCause cause) {
    return sessions
            .createQuery(
                """
                update WorkflowAttemptEntity a set a.stopCause = :cause
                where a.agentId = :agentId and a.stopCause is null
                """)
            .setParameter("agentId", agentId)
            .setParameter("cause", cause)
            .executeUpdate()
        == 1;
  }

  public record CheckSettlement(String nodeId, SystemCheckStatus status, String label) {}

  public record AttemptIdentity(String id, Integer number) {}

  public record RateLimitCounts(Long rejected, Long unresolved) {}

  public record ExternalEvidence(String bugId, String workspaceId, String workspaceFingerprint) {}

  public record AgentResult(
      String dependencyId,
      String key,
      String label,
      Integer sequence,
      String sourceNode,
      String sourceRun,
      String attemptId,
      String agentId,
      String result,
      Boolean workspaceRead,
      Boolean workspaceWrite) {}

  public record CheckResult(
      String dependencyId,
      String key,
      String label,
      Integer sequence,
      String runId,
      String checkId,
      SystemCheckStatus status,
      String summary,
      String evidence) {}

  public record ToolEvidenceRow(
      String toolId, String kind, Integer version, String resourceVersion) {
    ToolEvidence toEvidence() {
      return new ToolEvidence(
          UUID.fromString(toolId), ToolKind.valueOf(kind), version, resourceVersion);
    }
  }

  public record WriteEvidenceRow(String toolId, Integer version, String resourceVersion) {
    ToolEvidenceRow withKind() {
      return new ToolEvidenceRow(toolId, ToolKind.WORKSPACE_WRITE.name(), version, resourceVersion);
    }
  }

  public record AttemptView(
      String id,
      String nodeId,
      Integer number,
      String agentId,
      InvocationStatus status,
      String createdAt,
      String updatedAt,
      StopCause cause) {}

  public record CheckInputView(
      String checkId,
      String dependencyId,
      String kind,
      String sourceAttempt,
      String sourceCheck,
      String sourceRun,
      String sha256) {}

  public record CheckView(
      String id,
      String nodeId,
      String key,
      Integer attemptNo,
      String referenceId,
      Integer referenceVersion,
      SystemCheckStatus status,
      String summary,
      String evidence,
      String externalId,
      String inputsSha256,
      String startedAt,
      String finishedAt,
      String workspaceFingerprint) {}

  public record HandoffView(
      Integer schemaVersion,
      String acceptedAt,
      String senderAttempt,
      String senderNode,
      String senderAgent,
      String dependencyId,
      String receiverAttempt,
      String receiverNode,
      String receiverAgent) {}
}