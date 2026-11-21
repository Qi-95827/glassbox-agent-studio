package dev.workbench.persistence.repository;

import dev.workbench.domain.AgentRole;
import dev.workbench.domain.InvocationStatus;
import dev.workbench.domain.ModelKind;
import dev.workbench.domain.ToolKind;
import dev.workbench.persistence.entity.AgentInvocationEntity;
import dev.workbench.persistence.entity.ModelInvocationEntity;
import dev.workbench.persistence.entity.ToolAttemptEntity;
import dev.workbench.persistence.entity.ToolInvocationEntity;
import dev.workbench.persistence.repository.support.InvocationQueries;
import dev.workbench.persistence.repository.view.AgentData;
import dev.workbench.persistence.repository.view.EventOrigin;
import dev.workbench.persistence.repository.view.ModelData;
import dev.workbench.persistence.repository.view.TokenUsage;
import dev.workbench.persistence.repository.view.ToolData;
import dev.workbench.persistence.repository.view.ToolFingerprint;
import jakarta.persistence.EntityManager;
import java.util.List;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Agent、模型和工具调用的持久化。调用状态只允许从 RUNNING 结算。 */
@Repository
@Transactional(readOnly = true)
public class InvocationRepository {
  private final EntityManager sessions;

  public InvocationRepository(EntityManager sessions) {
    this.sessions = sessions;
  }

  // 展示与统计保留历史状态文本，无法识别的状态由统计层按未知处理。
  public List<AgentData> findAgentCalls(String runId) {
    return sessions
        .createQuery(InvocationQueries.AGENT_CALLS, AgentData.class)
        .setParameter("runId", runId)
        .getResultList();
  }

  public List<ModelData> findModelCalls(String runId) {
    return sessions
        .createQuery(InvocationQueries.MODEL_CALLS, ModelData.class)
        .setParameter("runId", runId)
        .getResultList();
  }

  public List<ToolData> findToolCalls(String runId) {
    return sessions
        .createQuery(InvocationQueries.TOOL_CALLS, ToolData.class)
        .setParameter("runId", runId)
        .getResultList();
  }

  @Transactional
  public void insertAgent(String id, String runId, AgentRole role, String goal, String time) {
    sessions.persist(
        new AgentInvocationEntity(
            id, runId, time, role.displayName(), goal, role.name(), role.version()));
    // 交接、尝试及定义记录在同一事务内用外键或归属校验引用此行。
    sessions.flush();
  }

  @Transactional
  public void insertModel(String id, String runId, String agentId, ModelKind model, String time) {
    sessions.persist(new ModelInvocationEntity(id, runId, time, agentId, model.code()));
  }

  @Transactional
  public void insertTool(
      String id, String runId, String agentId, String name, Integer version, String time) {
    sessions.persist(new ToolInvocationEntity(id, runId, time, agentId, name, version));
  }

  public String findRoleCode(String runId, String agentId) {
    return sessions
        .createQuery(InvocationQueries.ROLE_CODE, String.class)
        .setParameter("id", agentId)
        .setParameter("runId", runId)
        .getSingleResult();
  }

  public boolean hasAgentRole(String runId, String agentId, AgentRole role) {
    return sessions
            .createQuery(InvocationQueries.HAS_AGENT_ROLE, String.class)
            .setParameter("id", agentId)
            .setParameter("runId", runId)
            .setParameter("role", role.displayName())
            .getResultList().stream().findFirst().orElse(null)
        != null;
  }

  public boolean isRunningAgent(String agentId) {
    return sessions
            .createQuery(InvocationQueries.RUNNING_AGENT, String.class)
            .setParameter("id", agentId)
            .setParameter("running", InvocationStatus.RUNNING)
            .getResultList().stream().findFirst().orElse(null)
        != null;
  }

  public boolean isRunningReviewer(String runId, String agentId) {
    return sessions
            .createQuery(InvocationQueries.RUNNING_REVIEWER, String.class)
            .setParameter("id", agentId)
            .setParameter("runId", runId)
            .setParameter("running", InvocationStatus.RUNNING)
            .setParameter("role", AgentRole.REVIEWER.name())
            .getResultList().stream().findFirst().orElse(null)
        != null;
  }

  @Transactional
  public boolean recordDefinition(
      String runId, String agentId, String definitionId, int version, String sha256) {
    return sessions
            .createQuery(InvocationQueries.RECORD_DEFINITION)
            .setParameter("id", agentId)
            .setParameter("runId", runId)
            .setParameter("definitionId", definitionId)
            .setParameter("version", version)
            .setParameter("sha256", sha256)
            .setParameter("running", InvocationStatus.RUNNING)
            .executeUpdate()
        == 1;
  }

  @Transactional
  public boolean finishAgent(String id, InvocationStatus status, String result, String time) {
    return sessions
            .createQuery(InvocationQueries.FINISH_AGENT)
            .setParameter("id", id)
            .setParameter("status", status)
            .setParameter("result", result)
            .setParameter("time", time)
            .setParameter("running", InvocationStatus.RUNNING)
            .executeUpdate()
        == 1;
  }

  @Transactional
  public void finishModel(
      String id,
      InvocationStatus status,
      Integer input,
      Integer output,
      String error,
      long duration,
      String time) {
    sessions
        .createQuery(InvocationQueries.FINISH_MODEL)
        .setParameter("id", id)
        .setParameter("status", status)
        .setParameter("input", input)
        .setParameter("output", output)
        .setParameter("error", error)
        .setParameter("duration", duration)
        .setParameter("time", time)
        .setParameter("running", InvocationStatus.RUNNING)
        .executeUpdate();
  }

  @Transactional
  public void finishTool(
      String id,
      InvocationStatus status,
      String result,
      String error,
      long duration,
      String resourceVersion,
      String time) {
    sessions
        .createQuery(InvocationQueries.FINISH_TOOL)
        .setParameter("id", id)
        .setParameter("status", status)
        .setParameter("result", result)
        .setParameter("error", error)
        .setParameter("duration", duration)
        .setParameter("resourceVersion", resourceVersion)
        .setParameter("time", time)
        .setParameter("running", InvocationStatus.RUNNING)
        .executeUpdate();
  }

  public int countAgentModelCalls(String agentId) {
    return Math.toIntExact(
        sessions
            .createQuery(InvocationQueries.COUNT_AGENT_MODEL, Long.class)
            .setParameter("agentId", agentId)
            .getSingleResult());
  }

  public int countNodeModelCalls(String runId, String nodeId) {
    return Math.toIntExact(
        sessions
            .createQuery(InvocationQueries.COUNT_NODE_MODEL, Long.class)
            .setParameter("runId", runId)
            .setParameter("nodeId", nodeId)
            .getSingleResult());
  }

  public int countAgentToolCalls(String agentId) {
    return Math.toIntExact(
        sessions
            .createQuery(InvocationQueries.COUNT_AGENT_TOOL, Long.class)
            .setParameter("agentId", agentId)
            .getSingleResult());
  }

  public int countNodeToolCalls(String runId, String nodeId) {
    return Math.toIntExact(
        sessions
            .createQuery(InvocationQueries.COUNT_NODE_TOOL, Long.class)
            .setParameter("runId", runId)
            .setParameter("nodeId", nodeId)
            .getSingleResult());
  }

  public int countToolCalls(String runId, String toolName) {
    return Math.toIntExact(
        sessions
            .createQuery(InvocationQueries.COUNT_TOOL, Long.class)
            .setParameter("runId", runId)
            .setParameter("name", toolName)
            .getSingleResult());
  }

  public boolean hasCalls(String runId) {
    return sessions
            .createQuery(InvocationQueries.HAS_CALLS, String.class)
            .setParameter("id", runId)
            .getResultList().stream().findFirst().orElse(null)
        != null;
  }

  @Transactional
  public void insertToolFingerprint(
      String runId, String toolId, ToolKind kind, String fingerprint) {
    sessions.persist(new ToolAttemptEntity(runId, toolId, kind.name(), fingerprint));
  }

  public List<ToolFingerprint> findRecentFingerprints(String runId, int limit) {
    return sessions
        .createQuery(InvocationQueries.RECENT_FINGERPRINTS, ToolFingerprint.class)
        .setParameter("runId", runId)
        .setMaxResults(limit)
        .getResultList();
  }

  public boolean isCompletedAgent(String agentId) {
    Long count =
        sessions
            .createQuery(InvocationQueries.COMPLETED_AGENT, Long.class)
            .setParameter("id", agentId)
            .setParameter("completed", InvocationStatus.COMPLETED)
            .getSingleResult();
    return count != null && count > 0;
  }

  public String findCompletedAnalysis(String runId, String agentId) {
    return sessions
        .createQuery(InvocationQueries.COMPLETED_ANALYSIS, String.class)
        .setParameter("id", agentId)
        .setParameter("runId", runId)
        .setParameter("role", AgentRole.ANALYST.displayName())
        .setParameter("completed", InvocationStatus.COMPLETED)
        .getResultList().stream().findFirst().orElse(null);
  }

  public String readAgentResult(String runId, String agentId) {
    return sessions
        .createQuery(InvocationQueries.AGENT_RESULT, String.class)
        .setParameter("id", agentId)
        .setParameter("runId", runId)
        .getSingleResult();
  }

  public EventOrigin findEventOrigin(String runId, String agentId) {
    return sessions
        .createQuery(InvocationQueries.EVENT_ORIGIN, EventOrigin.class)
        .setParameter("runId", runId)
        .setParameter("id", agentId)
        .getResultList().stream().findFirst().orElse(null);
  }

  public TokenUsage summarizeTokens(String runId) {
    return sessions
        .createQuery(InvocationQueries.SUMMARIZE_TOKENS, TokenUsage.class)
        .setParameter("runId", runId)
        .getSingleResult();
  }

  @Transactional
  public void interruptRunning(String time) {
    sessions
        .createQuery(InvocationQueries.INTERRUPT_AGENTS)
        .setParameter("interrupted", InvocationStatus.INTERRUPTED)
        .setParameter("running", InvocationStatus.RUNNING)
        .setParameter("time", time)
        .executeUpdate();
    sessions
        .createQuery(InvocationQueries.INTERRUPT_TOOLS)
        .setParameter("interrupted", InvocationStatus.INTERRUPTED)
        .setParameter("running", InvocationStatus.RUNNING)
        .setParameter("time", time)
        .setParameter("message", "进程中断，结果未知；耗时未完整记录。")
        .executeUpdate();
    sessions
        .createQuery(InvocationQueries.INTERRUPT_MODELS)
        .setParameter("interrupted", InvocationStatus.INTERRUPTED)
        .setParameter("running", InvocationStatus.RUNNING)
        .setParameter("time", time)
        .setParameter("message", "进程中断，模型结果及用量未知。")
        .executeUpdate();
  }
}
