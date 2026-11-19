package dev.workbench.persistence.repository;

import jakarta.persistence.EntityManager;

import dev.workbench.domain.AgentRole;
import dev.workbench.domain.InvocationStatus;
import dev.workbench.domain.ModelKind;
import dev.workbench.domain.ToolKind;
import dev.workbench.persistence.entity.AgentInvocationEntity;
import dev.workbench.persistence.entity.ModelInvocationEntity;
import dev.workbench.persistence.entity.ToolAttemptEntity;
import dev.workbench.persistence.entity.ToolInvocationEntity;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedCaseInsensitiveMap;

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
        .createQuery(
            """
            select i.id, i.runId, cast(i.status as String), i.createdAt, i.updatedAt,
              i.roleName, i.taskGoal, i.result, i.roleCode, i.roleVersion, i.definitionId, i.definitionVersion, i.definitionSha256
            from AgentInvocationEntity i where i.runId = :runId order by i.createdAt
            """,
            AgentData.class)
        .setParameter("runId", runId)
        .getResultList();
  }

  public List<ModelData> findModelCalls(String runId) {
    return sessions
        .createQuery(
            """
            select i.id, i.runId, cast(i.status as String), i.createdAt, i.updatedAt,
              i.agentId, i.modelName, i.inputTokens, i.outputTokens, i.durationMs, i.errorMessage
            from ModelInvocationEntity i where i.runId = :runId order by i.createdAt
            """,
            ModelData.class)
        .setParameter("runId", runId)
        .getResultList();
  }

  public List<ToolData> findToolCalls(String runId) {
    return sessions
        .createQuery(
            """
            select i.id, i.runId, cast(i.status as String), i.createdAt, i.updatedAt,
              i.agentId, i.toolName, i.result, i.errorMessage, i.durationMs, i.toolVersion, i.resourceVersion
            from ToolInvocationEntity i where i.runId = :runId order by i.createdAt
            """,
            ToolData.class)
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
        .createQuery(
            "select i.roleCode from AgentInvocationEntity i where i.id = :id and i.runId = :runId",
            String.class)
        .setParameter("id", agentId)
        .setParameter("runId", runId)
        .getSingleResult();
  }

  public boolean hasAgentRole(String runId, String agentId, AgentRole role) {
    return sessions
            .createQuery(
                """
                select i.id from AgentInvocationEntity i
                where i.id = :id and i.runId = :runId and i.roleName = :role
                """,
                String.class)
            .setParameter("id", agentId)
            .setParameter("runId", runId)
            .setParameter("role", role.displayName())
            .getResultList().stream().findFirst().orElse(null)
        != null;
  }

  public boolean isRunningAgent(String agentId) {
    return sessions
            .createQuery(
                "select i.id from AgentInvocationEntity i where i.id = :id and i.status = :running",
                String.class)
            .setParameter("id", agentId)
            .setParameter("running", InvocationStatus.RUNNING)
            .getResultList().stream().findFirst().orElse(null)
        != null;
  }

  public boolean isRunningReviewer(String runId, String agentId) {
    return sessions
            .createQuery(
                """
                select i.id from AgentInvocationEntity i
                where i.id = :id and i.runId = :runId and i.status = :running and i.roleCode = :role
                """,
                String.class)
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
            .createQuery(
                """
                update AgentInvocationEntity i set i.definitionId = :definitionId,
                  i.definitionVersion = :version, i.definitionSha256 = :sha256
                where i.id = :id and i.runId = :runId and i.status = :running
                  and i.definitionId is null and i.definitionVersion is null and i.definitionSha256 is null
                """)
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
            .createQuery(
                """
                update AgentInvocationEntity i set i.status = :status, i.result = :result, i.updatedAt = :time
                where i.id = :id and i.status = :running
                """)
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
        .createQuery(
            """
            update ModelInvocationEntity i set i.status = :status, i.inputTokens = :input,
              i.outputTokens = :output, i.durationMs = :duration, i.errorMessage = :error, i.updatedAt = :time
            where i.id = :id and i.status = :running
            """)
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
        .createQuery(
            """
            update ToolInvocationEntity i set i.status = :status, i.result = :result,
              i.errorMessage = :error, i.durationMs = :duration,
              i.resourceVersion = :resourceVersion, i.updatedAt = :time
            where i.id = :id and i.status = :running
            """)
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
            .createQuery(
                "select count(i.id) from ModelInvocationEntity i where i.agentId = :agentId",
                Long.class)
            .setParameter("agentId", agentId)
            .getSingleResult());
  }

  public int countNodeModelCalls(String runId, String nodeId) {
    return Math.toIntExact(
        sessions
            .createQuery(
                """
                select count(i.id) from ModelInvocationEntity i join WorkflowAttemptEntity a on a.agentId = i.agentId
                where i.runId = :runId and a.nodeId = :nodeId
                """,
                Long.class)
            .setParameter("runId", runId)
            .setParameter("nodeId", nodeId)
            .getSingleResult());
  }

  public int countAgentToolCalls(String agentId) {
    return Math.toIntExact(
        sessions
            .createQuery(
                "select count(i.id) from ToolInvocationEntity i where i.agentId = :agentId",
                Long.class)
            .setParameter("agentId", agentId)
            .getSingleResult());
  }

  public int countNodeToolCalls(String runId, String nodeId) {
    return Math.toIntExact(
        sessions
            .createQuery(
                """
                select count(i.id) from ToolInvocationEntity i join WorkflowAttemptEntity a on a.agentId = i.agentId
                where i.runId = :runId and a.nodeId = :nodeId
                """,
                Long.class)
            .setParameter("runId", runId)
            .setParameter("nodeId", nodeId)
            .getSingleResult());
  }

  public int countToolCalls(String runId, String toolName) {
    return Math.toIntExact(
        sessions
            .createQuery(
                "select count(i.id) from ToolInvocationEntity i where i.runId = :runId and"
                    + " i.toolName = :name",
                Long.class)
            .setParameter("runId", runId)
            .setParameter("name", toolName)
            .getSingleResult());
  }

  public boolean hasCalls(String runId) {
    return sessions
            .createQuery(
                """
                select r.id from RunEntity r where r.id = :id and (
                  exists (select a.id from AgentInvocationEntity a where a.runId = :id)
                  or exists (select m.id from ModelInvocationEntity m where m.runId = :id)
                  or exists (select t.id from ToolInvocationEntity t where t.runId = :id)
                )
                """,
                String.class)
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
        .createQuery(
            """
            select t.kind, t.fingerprint from ToolAttemptEntity t
            where t.runId = :runId order by t.id desc
            """,
            ToolFingerprint.class)
        .setParameter("runId", runId)
        .setMaxResults(limit)
        .getResultList();
  }

  public boolean isCompletedAgent(String agentId) {
    Long count =
        sessions
            .createQuery(
                "select count(i) from AgentInvocationEntity i"
                    + " where i.id = :id and i.status = :completed",
                Long.class)
            .setParameter("id", agentId)
            .setParameter("completed", InvocationStatus.COMPLETED)
            .getSingleResult();
    return count != null && count > 0;
  }

  public String findCompletedAnalysis(String runId, String agentId) {
    return sessions
        .createQuery(
            """
            select i.result from AgentInvocationEntity i where i.id = :id and i.runId = :runId
              and i.roleName = :role and i.status = :completed
            """,
            String.class)
        .setParameter("id", agentId)
        .setParameter("runId", runId)
        .setParameter("role", AgentRole.ANALYST.displayName())
        .setParameter("completed", InvocationStatus.COMPLETED)
        .getResultList().stream().findFirst().orElse(null);
  }

  public String readAgentResult(String runId, String agentId) {
    return sessions
        .createQuery(
            "select i.result from AgentInvocationEntity i where i.id = :id and i.runId = :runId",
            String.class)
        .setParameter("id", agentId)
        .setParameter("runId", runId)
        .getSingleResult();
  }

  public EventOrigin findEventOrigin(String runId, String agentId) {
    return sessions
        .createQuery(
            """
            select n.planVersionId, n.id, a.id from AgentInvocationEntity i
            left join PlanNodeEntity n on n.runId = i.runId and n.agentId = i.id
            left join WorkflowAttemptEntity a on a.agentId = i.id and a.nodeId = n.id
            where i.runId = :runId and i.id = :id
            """,
            EventOrigin.class)
        .setParameter("runId", runId)
        .setParameter("id", agentId)
        .getResultList().stream().findFirst().orElse(null);
  }

  public record EventOrigin(String planId, String nodeId, String attemptId) {}

  public record ToolFingerprint(String kind, String fingerprint) {}

  public TokenUsage summarizeTokens(String runId) {
    return sessions
        .createQuery(
            """
            select coalesce(sum(cast(coalesce(i.inputTokens, 0) as Long)
              + cast(coalesce(i.outputTokens, 0) as Long)), 0),
              coalesce(sum(case when i.inputTokens is null or i.outputTokens is null then 1 else 0 end), 0)
            from ModelInvocationEntity i where i.runId = :runId
            """,
            TokenUsage.class)
        .setParameter("runId", runId)
        .getSingleResult();
  }

  @Transactional
  public void interruptRunning(String time) {
    sessions
        .createQuery(
            """
            update AgentInvocationEntity i set i.status = :interrupted, i.updatedAt = :time
            where i.status = :running
            """)
        .setParameter("interrupted", InvocationStatus.INTERRUPTED)
        .setParameter("running", InvocationStatus.RUNNING)
        .setParameter("time", time)
        .executeUpdate();
    sessions
        .createQuery(
            """
            update ToolInvocationEntity i set i.status = :interrupted, i.updatedAt = :time,
              i.errorMessage = :message where i.status = :running
            """)
        .setParameter("interrupted", InvocationStatus.INTERRUPTED)
        .setParameter("running", InvocationStatus.RUNNING)
        .setParameter("time", time)
        .setParameter("message", "进程中断，结果未知；耗时未完整记录。")
        .executeUpdate();
    sessions
        .createQuery(
            """
            update ModelInvocationEntity i set i.status = :interrupted, i.updatedAt = :time,
              i.errorMessage = :message where i.status = :running
            """)
        .setParameter("interrupted", InvocationStatus.INTERRUPTED)
        .setParameter("running", InvocationStatus.RUNNING)
        .setParameter("time", time)
        .setParameter("message", "进程中断，模型结果及用量未知。")
        .executeUpdate();
  }

  public record TokenUsage(Long known, Long unknownCalls) {}

  public record AgentData(
      String id,
      String runId,
      String status,
      String createdAt,
      String updatedAt,
      String roleName,
      String taskGoal,
      String result,
      String roleCode,
      Integer roleVersion,
      String definitionId,
      Integer definitionVersion,
      String definitionSha256) {
    public Map<String, Object> apiFields() {
      var fields = new LinkedCaseInsensitiveMap<Object>();
      fields.put("ID", id);
      fields.put("RUN_ID", runId);
      fields.put("STATUS", status);
      fields.put("CREATED_AT", createdAt);
      fields.put("UPDATED_AT", updatedAt);
      fields.put("ROLE_NAME", roleName);
      fields.put("TASK_GOAL", taskGoal);
      fields.put("RESULT", result);
      fields.put("ROLE_CODE", roleCode);
      fields.put("ROLE_VERSION", roleVersion);
      fields.put("DEFINITION_ID", definitionId);
      fields.put("DEFINITION_VERSION", definitionVersion);
      fields.put("DEFINITION_SHA256", definitionSha256);
      return fields;
    }
  }

  public record ModelData(
      String id,
      String runId,
      String status,
      String createdAt,
      String updatedAt,
      String agentId,
      String modelName,
      Integer inputTokens,
      Integer outputTokens,
      Long durationMs,
      String errorMessage) {
    public Map<String, Object> apiFields() {
      var fields = new LinkedCaseInsensitiveMap<Object>();
      fields.put("ID", id);
      fields.put("RUN_ID", runId);
      fields.put("STATUS", status);
      fields.put("CREATED_AT", createdAt);
      fields.put("UPDATED_AT", updatedAt);
      fields.put("AGENT_ID", agentId);
      fields.put("MODEL_NAME", modelName);
      fields.put("INPUT_TOKENS", inputTokens);
      fields.put("OUTPUT_TOKENS", outputTokens);
      fields.put("DURATION_MS", durationMs);
      fields.put("ERROR_MESSAGE", errorMessage);
      return fields;
    }
  }

  public record ToolData(
      String id,
      String runId,
      String status,
      String createdAt,
      String updatedAt,
      String agentId,
      String toolName,
      String result,
      String errorMessage,
      Long durationMs,
      Integer toolVersion,
      String resourceVersion) {
    public Map<String, Object> apiFields() {
      var fields = new LinkedCaseInsensitiveMap<Object>();
      fields.put("ID", id);
      fields.put("RUN_ID", runId);
      fields.put("STATUS", status);
      fields.put("CREATED_AT", createdAt);
      fields.put("UPDATED_AT", updatedAt);
      fields.put("AGENT_ID", agentId);
      fields.put("TOOL_NAME", toolName);
      fields.put("RESULT", result);
      fields.put("ERROR_MESSAGE", errorMessage);
      fields.put("DURATION_MS", durationMs);
      fields.put("TOOL_VERSION", toolVersion);
      fields.put("RESOURCE_VERSION", resourceVersion);
      return fields;
    }
  }
}