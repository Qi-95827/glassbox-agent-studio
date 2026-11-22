package dev.workbench.persistence.repository;

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
import dev.workbench.persistence.repository.support.WorkflowEvidenceMapper;
import dev.workbench.persistence.repository.support.WorkflowExecutionQueries;
import dev.workbench.persistence.repository.support.WorkflowRetryPolicy;
import dev.workbench.persistence.repository.view.AgentResult;
import dev.workbench.persistence.repository.view.AttemptIdentity;
import dev.workbench.persistence.repository.view.AttemptView;
import dev.workbench.persistence.repository.view.CheckInputView;
import dev.workbench.persistence.repository.view.CheckReceipt;
import dev.workbench.persistence.repository.view.CheckResult;
import dev.workbench.persistence.repository.view.CheckSettlement;
import dev.workbench.persistence.repository.view.CheckView;
import dev.workbench.persistence.repository.view.ExternalEvidence;
import dev.workbench.persistence.repository.view.HandoffView;
import dev.workbench.persistence.repository.view.RateLimitCounts;
import dev.workbench.persistence.repository.view.ToolEvidenceRow;
import dev.workbench.persistence.repository.view.WriteEvidenceRow;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 动态尝试、系统检查、依赖结果和接收证据的数据库操作。 */
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
        .createQuery(WorkflowExecutionQueries.NEXT_ATTEMPT, Integer.class)
        .setParameter("nodeId", nodeId)
        .getSingleResult();
  }

  public int nextCheckNumber(String nodeId) {
    return sessions
        .createQuery(WorkflowExecutionQueries.NEXT_CHECK, Integer.class)
        .setParameter("nodeId", nodeId)
        .getSingleResult();
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void insertAttempt(String id, String nodeId, int number, String agentId) {
    sessions.persist(new WorkflowAttemptEntity(id, nodeId, number, agentId));
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void insertAttempt(
      String id, String nodeId, int number, String agentId, int revisionIndex) {
    sessions.persist(new WorkflowAttemptEntity(id, nodeId, number, agentId, revisionIndex));
  }

  /** 把审查结论写入产生它的尝试；后续目标重试从回边读取同一结论作为批评反馈。 */
  @Transactional(propagation = Propagation.MANDATORY)
  public void recordCriticVerdict(String agentId, String verdictJson) {
    sessions
        .createQuery(
            "update WorkflowAttemptEntity a set a.criticVerdictJson = :verdict where a.agentId = :agentId")
        .setParameter("verdict", verdictJson)
        .setParameter("agentId", agentId)
        .executeUpdate();
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
        .createQuery(WorkflowExecutionQueries.CHECK_SETTLEMENT, CheckSettlement.class)
        .setParameter("id", checkId)
        .setParameter("runId", runId)
        .getResultList()
        .stream()
        .findFirst();
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public boolean finishCheck(String checkId, SystemCheckResult result, String time) {
    return sessions
            .createQuery(WorkflowExecutionQueries.FINISH_CHECK)
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

  /** 没有其他失败或未结算模型调用时，只允许一次明确的限流拒绝。 */
  public boolean canRetryRateLimit(String runId, String agentId, String safeMessage) {
    var info =
        sessions
            .createQuery(WorkflowExecutionQueries.RATE_LIMIT_COUNTS, RateLimitCounts.class)
            .setParameter("runId", runId)
            .setParameter("agentId", agentId)
            .setParameter("failed", InvocationStatus.FAILED)
            .setParameter("completed", InvocationStatus.COMPLETED)
            .setParameter("message", safeMessage)
            .getSingleResult();
    return WorkflowRetryPolicy.onlySingleRateLimitRejection(info);
  }

  public String findExternalReference(String checkId) {
    var rows =
        sessions
            .createQuery(WorkflowExecutionQueries.EXTERNAL_REFERENCE, String.class)
            .setParameter("id", checkId)
            .getResultList();
    return rows.isEmpty() ? null : rows.getFirst();
  }

  public Optional<ExternalEvidence> findExternalEvidence(String externalId) {
    return sessions
        .createQuery(WorkflowExecutionQueries.EXTERNAL_EVIDENCE, ExternalEvidence.class)
        .setParameter("id", externalId)
        .getResultList()
        .stream()
        .findFirst();
  }

  public List<AgentResult> findAgentResults(String runId, String recipient) {
    return sessions
        .createQuery(WorkflowExecutionQueries.AGENT_RESULTS, AgentResult.class)
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
        .createQuery(WorkflowExecutionQueries.CHECK_RESULTS, CheckResult.class)
        .setParameter("runId", runId)
        .setParameter("recipient", recipient)
        .setParameter("checkKind", PlanNodeKind.SYSTEM_CHECK)
        .setParameter("completed", PlanNodeStatus.COMPLETED)
        .setParameter("passed", SystemCheckStatus.PASSED)
        .getResultList();
  }

  public List<ToolEvidence> findToolEvidence(
      String runId, String agentId, boolean writeAuthorized) {
    var rows =
        sessions
            .createQuery(WorkflowExecutionQueries.TOOL_EVIDENCE, ToolEvidenceRow.class)
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
            .getResultList();
    return WorkflowEvidenceMapper.toEvidence(rows);
  }

  public List<ToolEvidence> findAcceptedToolEvidence(String receiver, String dependency) {
    var reads =
        sessions
            .createQuery(
                WorkflowExecutionQueries.ACCEPTED_READ_EVIDENCE, ToolEvidenceRow.class)
            .setParameter("receiver", receiver)
            .setParameter("dependency", dependency)
            .getResultList();
    var writes =
        sessions
            .createQuery(
                WorkflowExecutionQueries.ACCEPTED_WRITE_EVIDENCE, WriteEvidenceRow.class)
            .setParameter("receiver", receiver)
            .setParameter("dependency", dependency)
            .getResultList();
    return WorkflowEvidenceMapper.merge(reads, writes);
  }

  public List<AttemptView> findAttempts(String runId) {
    return sessions
        .createQuery(WorkflowExecutionQueries.ATTEMPTS, AttemptView.class)
        .setParameter("runId", runId)
        .getResultList();
  }

  public List<CheckInputView> findCheckInputs(String runId) {
    return sessions
        .createQuery(WorkflowExecutionQueries.CHECK_INPUTS, CheckInputView.class)
        .setParameter("runId", runId)
        .getResultList();
  }

  public List<CheckReceipt> findCheckReceipts(String runId, String nodeId) {
    return sessions
        .createQuery(WorkflowExecutionQueries.CHECK_RECEIPTS, CheckReceipt.class)
        .setParameter("runId", runId)
        .setParameter("nodeId", nodeId)
        .getResultList();
  }

  public List<CheckView> findChecks(String runId) {
    return sessions
        .createQuery(WorkflowExecutionQueries.CHECKS, CheckView.class)
        .setParameter("runId", runId)
        .getResultList();
  }

  public List<HandoffView> findHandoffs(String runId) {
    return sessions
        .createQuery(WorkflowExecutionQueries.HANDOFFS, HandoffView.class)
        .setParameter("runId", runId)
        .getResultList();
  }

  public boolean isRunningAttempt(String runId, String agentId) {
    return sessions
            .createQuery(WorkflowExecutionQueries.RUNNING_ATTEMPT, String.class)
            .setParameter("runId", runId)
            .setParameter("agentId", agentId)
            .setParameter("running", PlanNodeStatus.RUNNING)
            .getResultList()
            .stream()
            .findFirst()
            .orElse(null)
        != null;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void interruptChecks(String runId, String time) {
    sessions
        .createQuery(WorkflowExecutionQueries.INTERRUPT_CHECKS)
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
            .createQuery(WorkflowExecutionQueries.RECORD_STOP_CAUSE)
            .setParameter("agentId", agentId)
            .setParameter("cause", cause)
            .executeUpdate()
        == 1;
  }
}
