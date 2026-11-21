package dev.workbench.persistence.repository.support;

/** {@code WorkflowExecutionRepository} 使用的 JPQL 查询文本。 */
public final class WorkflowExecutionQueries {
  private WorkflowExecutionQueries() {}

  public static final String NEXT_ATTEMPT =
      "select coalesce(max(a.attemptNo), 0) + 1 from WorkflowAttemptEntity a where a.nodeId ="
          + " :nodeId";

  public static final String NEXT_CHECK =
      "select coalesce(max(c.attemptNo), 0) + 1 from WorkflowCheckEntity c where c.nodeId ="
          + " :nodeId";

  public static final String CHECK_SETTLEMENT =
      """
      select c.nodeId, c.status, n.nodeLabel
      from WorkflowCheckEntity c join PlanNodeEntity n on n.id = c.nodeId
      where c.id = :id and n.runId = :runId
      """;

  public static final String FINISH_CHECK =
      """
      update WorkflowCheckEntity c set c.status = :status, c.summary = :summary,
        c.evidence = :evidence, c.externalEvidenceId = :external, c.finishedAt = :time
      where c.id = :id and c.status = :running
      """;

  public static final String RATE_LIMIT_COUNTS =
      """
      select coalesce(sum(case when m.status = :failed and m.errorMessage = :message then 1 else 0 end), 0),
        coalesce(sum(case when m.status <> :completed and
          (m.status <> :failed or m.errorMessage is distinct from :message) then 1 else 0 end), 0)
      from ModelInvocationEntity m where m.runId = :runId and m.agentId = :agentId
      """;

  public static final String EXTERNAL_REFERENCE =
      "select c.externalEvidenceId from WorkflowCheckEntity c where c.id = :id";

  public static final String EXTERNAL_EVIDENCE =
      """
      select b.bugId, b.workspaceId, b.workspaceFingerprint
      from BugCheckReferenceEntity b where b.id = :id
      """;

  public static final String AGENT_RESULTS =
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
      """;

  public static final String CHECK_RESULTS =
      """
      select n.id, n.nodeKey, n.nodeLabel, n.sequenceNo, n.runId, c.id, c.status, c.summary, c.evidence
      from PlanNodeEntity n join WorkflowCheckEntity c on c.nodeId = n.id
        and c.attemptNo = (select max(x.attemptNo) from WorkflowCheckEntity x where x.nodeId = n.id)
      where n.runId = :runId and n.nodeKind = :checkKind and n.status = :completed
        and c.status = :passed
        and (:recipient is null or exists (select d.id.nodeId from PlanNodeDependencyEntity d
          where d.id.runId = n.runId and d.id.nodeId = :recipient and d.id.prerequisiteId = n.id))
      order by n.sequenceNo
      """;

  public static final String TOOL_EVIDENCE =
      """
      select i.id, g.id.toolKind, i.toolVersion, i.resourceVersion
      from ToolInvocationEntity i
      join AgentToolGrantEntity g on g.id.agentId = i.agentId and g.toolVersion = i.toolVersion
      where i.runId = :runId and i.agentId = :agentId and i.status = :completed
        and ((g.id.toolKind = :read and i.toolName = :readName)
          or (g.id.toolKind = :search and i.toolName = :searchName)
          or (g.id.toolKind = :write and i.toolName = :writeName and :writeAllowed = true))
      order by i.createdAt, i.id
      """;

  public static final String ACCEPTED_READ_EVIDENCE =
      """
      select e.id.toolId, e.toolKind, e.toolVersion, e.resourceVersion
      from WorkflowHandoffReadEvidenceEntity e
      where e.id.receiverAttemptId = :receiver and e.id.dependencyNodeId = :dependency
      """;

  public static final String ACCEPTED_WRITE_EVIDENCE =
      """
      select e.id.toolId, e.toolVersion, e.resourceVersion
      from WorkflowHandoffWriteEvidenceEntity e
      where e.id.receiverAttemptId = :receiver and e.id.dependencyNodeId = :dependency
      """;

  public static final String ATTEMPTS =
      """
      select a.id, a.nodeId, a.attemptNo, a.agentId, i.status, i.createdAt, i.updatedAt, a.stopCause
      from WorkflowAttemptEntity a join AgentInvocationEntity i on i.id = a.agentId
      join PlanNodeEntity n on n.id = a.nodeId and n.runId = i.runId
      where n.runId = :runId order by n.sequenceNo, a.attemptNo
      """;

  public static final String CHECK_INPUTS =
      """
      select i.id.checkId, i.id.dependencyNodeId, i.sourceKind, i.sourceAttemptId,
        i.sourceCheckId, i.sourceRunId, i.resultSha256, a.result, sourceCheck.summary
      from WorkflowCheckInputEntity i join WorkflowCheckEntity c on c.id = i.id.checkId
      join PlanNodeEntity n on n.id = c.nodeId
      join PlanNodeEntity d on d.id = i.id.dependencyNodeId
        and d.runId = n.runId and d.planVersionId = n.planVersionId
      join PlanNodeDependencyEntity edge on edge.id.runId = n.runId
        and edge.id.nodeId = n.id and edge.id.prerequisiteId = d.id
      join RunEntity currentRun on currentRun.id = n.runId
      left join WorkflowAttemptEntity attempt on attempt.id = i.sourceAttemptId
      left join PlanNodeEntity sourceNode on sourceNode.id = attempt.nodeId
        and sourceNode.runId = i.sourceRunId
      left join AgentInvocationEntity a on a.id = attempt.agentId and a.runId = sourceNode.runId
        and ((sourceNode.id = d.id and sourceNode.runId = n.runId)
          or (sourceNode.runId = currentRun.parentId and d.sourceAgentId = a.id
            and sourceNode.nodeKey = d.nodeKey))
      left join WorkflowCheckEntity sourceCheck on sourceCheck.id = i.sourceCheckId
        and sourceCheck.nodeId = d.id and d.runId = i.sourceRunId
      where n.runId = :runId order by i.id.checkId, d.sequenceNo
      """;

  public static final String CHECK_RECEIPTS =
      """
      select c.id, source.id, source.nodeLabel, n.id, n.nodeLabel, a.id,
        h.acceptedAt, c.summary, c.evidence
      from WorkflowHandoffCheckEntity h
      join WorkflowAttemptEntity a on a.id = h.id.receiverAttemptId
      join PlanNodeEntity n on n.id = a.nodeId
      join WorkflowCheckEntity c on c.id = h.checkId
      join PlanNodeEntity source on source.id = c.nodeId and source.id = h.id.dependencyNodeId
        and source.runId = n.runId and source.planVersionId = n.planVersionId
      join PlanNodeDependencyEntity d on d.id.runId = n.runId and d.id.nodeId = n.id
        and d.id.prerequisiteId = source.id
      where n.runId = :runId and (n.id = :nodeId or source.id = :nodeId)
      order by n.sequenceNo, a.attemptNo, source.sequenceNo
      """;

  public static final String CHECKS =
      """
      select c.id, c.nodeId, n.nodeKey, c.attemptNo, c.checkId, c.checkVersion, c.status,
        c.summary, c.evidence, c.externalEvidenceId, c.inputsSha256, c.startedAt, c.finishedAt,
        b.workspaceFingerprint
      from WorkflowCheckEntity c join PlanNodeEntity n on n.id = c.nodeId
      left join BugCheckReferenceEntity b on b.id = c.externalEvidenceId
      where n.runId = :runId order by n.sequenceNo, c.attemptNo
      """;

  public static final String HANDOFFS =
      """
      select h.schemaVersion, h.acceptedAt, s.id, s.nodeId, s.agentId,
        h.id.dependencyNodeId, r.id, r.nodeId, r.agentId, i.runId, i.result
      from WorkflowHandoffEntity h join WorkflowAttemptEntity r on r.id = h.id.receiverAttemptId
      join WorkflowAttemptEntity s on s.id = h.senderAttemptId
      join PlanNodeEntity n on n.id = r.nodeId join PlanNodeEntity p on p.id = s.nodeId
      join PlanNodeEntity d on d.id = h.id.dependencyNodeId and d.runId = n.runId
      join PlanNodeDependencyEntity e on e.id.runId = n.runId and e.id.nodeId = n.id
        and e.id.prerequisiteId = d.id
      join RunEntity currentRun on currentRun.id = n.runId
      left join AgentInvocationEntity i on i.id = s.agentId and i.runId = p.runId
        and ((p.runId = n.runId and p.id = d.id)
          or (p.runId = currentRun.parentId and d.sourceAgentId = s.agentId
            and d.nodeKey = p.nodeKey))
      where n.runId = :runId and d.planVersionId = n.planVersionId
      order by n.sequenceNo, r.attemptNo, p.sequenceNo, s.attemptNo
      """;

  public static final String RUNNING_ATTEMPT =
      """
      select a.id from WorkflowAttemptEntity a
      join PlanNodeEntity n on n.id = a.nodeId and n.agentId = a.agentId
      where n.runId = :runId and a.agentId = :agentId
        and n.planVersionId is not null and n.status = :running
      """;

  public static final String INTERRUPT_CHECKS =
      """
      update WorkflowCheckEntity c set c.status = :interrupted,
        c.summary = coalesce(c.summary, :unknown), c.finishedAt = :time
      where c.status = :running and c.nodeId in (select n.id from PlanNodeEntity n where n.runId = :runId)
      """;

  public static final String RECORD_STOP_CAUSE =
      """
      update WorkflowAttemptEntity a set a.stopCause = :cause
      where a.agentId = :agentId and a.stopCause is null
      """;
}
