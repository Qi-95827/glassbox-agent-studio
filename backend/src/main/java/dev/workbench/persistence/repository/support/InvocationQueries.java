package dev.workbench.persistence.repository.support;

/** {@code InvocationRepository} 使用的 JPQL 查询文本。 */
public final class InvocationQueries {
  private InvocationQueries() {}

  public static final String AGENT_CALLS =
      """
      select i.id, i.runId, cast(i.status as String), i.createdAt, i.updatedAt,
        i.roleName, i.taskGoal, i.result, i.roleCode, i.roleVersion, i.definitionId, i.definitionVersion, i.definitionSha256
      from AgentInvocationEntity i where i.runId = :runId order by i.createdAt
      """;

  public static final String MODEL_CALLS =
      """
      select i.id, i.runId, cast(i.status as String), i.createdAt, i.updatedAt,
        i.agentId, i.modelName, i.inputTokens, i.outputTokens, i.durationMs, i.errorMessage
      from ModelInvocationEntity i where i.runId = :runId order by i.createdAt
      """;

  public static final String TOOL_CALLS =
      """
      select i.id, i.runId, cast(i.status as String), i.createdAt, i.updatedAt,
        i.agentId, i.toolName, i.result, i.errorMessage, i.durationMs, i.toolVersion, i.resourceVersion
      from ToolInvocationEntity i where i.runId = :runId order by i.createdAt
      """;

  public static final String ROLE_CODE =
      "select i.roleCode from AgentInvocationEntity i where i.id = :id and i.runId = :runId";

  public static final String HAS_AGENT_ROLE =
      """
      select i.id from AgentInvocationEntity i
      where i.id = :id and i.runId = :runId and i.roleName = :role
      """;

  public static final String RUNNING_AGENT =
      "select i.id from AgentInvocationEntity i where i.id = :id and i.status = :running";

  public static final String RUNNING_REVIEWER =
      """
      select i.id from AgentInvocationEntity i
      where i.id = :id and i.runId = :runId and i.status = :running and i.roleCode = :role
      """;

  public static final String RECORD_DEFINITION =
      """
      update AgentInvocationEntity i set i.definitionId = :definitionId,
        i.definitionVersion = :version, i.definitionSha256 = :sha256
      where i.id = :id and i.runId = :runId and i.status = :running
        and i.definitionId is null and i.definitionVersion is null and i.definitionSha256 is null
      """;

  public static final String FINISH_AGENT =
      """
      update AgentInvocationEntity i set i.status = :status, i.result = :result, i.updatedAt = :time
      where i.id = :id and i.status = :running
      """;

  public static final String FINISH_MODEL =
      """
      update ModelInvocationEntity i set i.status = :status, i.inputTokens = :input,
        i.outputTokens = :output, i.durationMs = :duration, i.errorMessage = :error, i.updatedAt = :time
      where i.id = :id and i.status = :running
      """;

  public static final String FINISH_TOOL =
      """
      update ToolInvocationEntity i set i.status = :status, i.result = :result,
        i.errorMessage = :error, i.durationMs = :duration,
        i.resourceVersion = :resourceVersion, i.updatedAt = :time
      where i.id = :id and i.status = :running
      """;

  public static final String COUNT_AGENT_MODEL =
      "select count(i.id) from ModelInvocationEntity i where i.agentId = :agentId";

  public static final String COUNT_NODE_MODEL =
      """
      select count(i.id) from ModelInvocationEntity i join WorkflowAttemptEntity a on a.agentId = i.agentId
      where i.runId = :runId and a.nodeId = :nodeId
      """;

  public static final String COUNT_AGENT_TOOL =
      "select count(i.id) from ToolInvocationEntity i where i.agentId = :agentId";

  public static final String COUNT_NODE_TOOL =
      """
      select count(i.id) from ToolInvocationEntity i join WorkflowAttemptEntity a on a.agentId = i.agentId
      where i.runId = :runId and a.nodeId = :nodeId
      """;

  public static final String COUNT_TOOL =
      "select count(i.id) from ToolInvocationEntity i where i.runId = :runId and"
          + " i.toolName = :name";

  public static final String HAS_CALLS =
      """
      select r.id from RunEntity r where r.id = :id and (
        exists (select a.id from AgentInvocationEntity a where a.runId = :id)
        or exists (select m.id from ModelInvocationEntity m where m.runId = :id)
        or exists (select t.id from ToolInvocationEntity t where t.runId = :id)
      )
      """;

  public static final String RECENT_FINGERPRINTS =
      """
      select t.kind, t.fingerprint from ToolAttemptEntity t
      where t.runId = :runId order by t.id desc
      """;

  public static final String COMPLETED_AGENT =
      "select count(i) from AgentInvocationEntity i"
          + " where i.id = :id and i.status = :completed";

  public static final String COMPLETED_ANALYSIS =
      """
      select i.result from AgentInvocationEntity i where i.id = :id and i.runId = :runId
        and i.roleName = :role and i.status = :completed
      """;

  public static final String AGENT_RESULT =
      "select i.result from AgentInvocationEntity i where i.id = :id and i.runId = :runId";

  public static final String EVENT_ORIGIN =
      """
      select n.planVersionId, n.id, a.id from AgentInvocationEntity i
      left join PlanNodeEntity n on n.runId = i.runId and n.agentId = i.id
      left join WorkflowAttemptEntity a on a.agentId = i.id and a.nodeId = n.id
      where i.runId = :runId and i.id = :id
      """;

  public static final String SUMMARIZE_TOKENS =
      """
      select coalesce(sum(cast(coalesce(i.inputTokens, 0) as Long)
        + cast(coalesce(i.outputTokens, 0) as Long)), 0),
        coalesce(sum(case when i.inputTokens is null or i.outputTokens is null then 1 else 0 end), 0)
      from ModelInvocationEntity i where i.runId = :runId
      """;

  public static final String INTERRUPT_AGENTS =
      """
      update AgentInvocationEntity i set i.status = :interrupted, i.updatedAt = :time
      where i.status = :running
      """;

  public static final String INTERRUPT_TOOLS =
      """
      update ToolInvocationEntity i set i.status = :interrupted, i.updatedAt = :time,
        i.errorMessage = :message where i.status = :running
      """;

  public static final String INTERRUPT_MODELS =
      """
      update ModelInvocationEntity i set i.status = :interrupted, i.updatedAt = :time,
        i.errorMessage = :message where i.status = :running
      """;
}
