package dev.workbench.task;

import dev.workbench.framework.agent.assembly.AgentAssemblySnapshot;
import dev.workbench.framework.workflow.check.SystemCheckRef;
import dev.workbench.framework.workflow.plan.PlanNodeKind;
import dev.workbench.framework.workflow.plan.WorkflowAttempt;
import dev.workbench.framework.workflow.plan.WorkflowCheck;
import dev.workbench.framework.workflow.plan.WorkflowHandoff;
import dev.workbench.persistence.repository.WorkflowExecutionRepository;
import dev.workbench.persistence.repository.view.CheckReceipt;
import dev.workbench.store.RunStateStore;
import dev.workbench.store.TaskRecordService;
import dev.workbench.workspace.WorkspaceService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 执行视图组装：把尝试、系统检查、检查收据与交接记录投影为只读视图。
 *
 * <p>只读，不修改任何运行状态；检查输入只在摘要与正文一致时才回填正文，避免展示被改写的来源。
 */
final class WorkflowViewAssembler {
  private final WorkflowExecutionRepository records;
  private final TaskRecordService tasks;
  private final RunStateStore runs;
  private final WorkspaceService workspaces;

  WorkflowViewAssembler(
      WorkflowExecutionRepository records,
      TaskRecordService tasks,
      RunStateStore runs,
      WorkspaceService workspaces) {
    this.records = records;
    this.tasks = tasks;
    this.runs = runs;
    this.workspaces = workspaces;
  }

  /** 按计划顺序返回节点尝试。 */
  List<WorkflowAttempt> attempts(String runId) {
    return records.findAttempts(runId).stream()
        .map(
            row ->
                new WorkflowAttempt(
                    UUID.fromString(row.id()),
                    UUID.fromString(row.nodeId()),
                    row.number(),
                    UUID.fromString(row.agentId()),
                    row.status(),
                    Instant.parse(row.createdAt()),
                    Instant.parse(row.updatedAt()),
                    row.cause()))
        .toList();
  }

  /** 按计划顺序返回系统检查尝试。 */
  List<WorkflowCheck> checks(String runId) {
    var inputsByCheck = new java.util.LinkedHashMap<UUID, List<WorkflowCheck.Input>>();
    for (var row : records.findCheckInputs(runId)) {
      var kind = PlanNodeKind.valueOf(row.kind());
      String body = kind == PlanNodeKind.SYSTEM_CHECK ? row.checkSummary() : row.agentResult();
      boolean sourceMatches =
          body != null && AgentAssemblySnapshot.sha256(body).equals(row.sha256());
      inputsByCheck
          .computeIfAbsent(UUID.fromString(row.checkId()), key -> new java.util.ArrayList<>())
          .add(
              new WorkflowCheck.Input(
                  UUID.fromString(row.dependencyId()),
                  kind,
                  nullableUuid(row.sourceAttempt()),
                  nullableUuid(row.sourceCheck()),
                  UUID.fromString(row.sourceRun()),
                  row.sha256(),
                  sourceMatches ? body : null));
    }
    var task = tasks.taskDefinition(runs.runSnapshot(runId).taskId().toString());
    String fingerprint = currentWorkspaceFingerprint(task.workspaceId());
    return records.findChecks(runId).stream()
        .map(
            row -> {
              UUID id = UUID.fromString(row.id());
              return new WorkflowCheck(
                  id,
                  UUID.fromString(row.nodeId()),
                  row.key(),
                  row.attemptNo(),
                  new SystemCheckRef(row.referenceId(), row.referenceVersion()),
                  row.status(),
                  row.summary(),
                  row.evidence(),
                  row.externalId(),
                  row.externalId() == null
                      ? null
                      : row.workspaceFingerprint() != null
                          && row.workspaceFingerprint().equals(fingerprint),
                  row.inputsSha256(),
                  Instant.parse(row.startedAt()),
                  row.finishedAt() == null ? null : Instant.parse(row.finishedAt()),
                  inputsByCheck.getOrDefault(id, List.of()));
            })
        .toList();
  }

  /** 某节点的检查接收收据。 */
  List<CheckReceipt> checkReceipts(String runId, UUID nodeId) {
    return records.findCheckReceipts(runId, nodeId.toString());
  }

  /** 已接收的前置交接，含被接受工具证据。 */
  List<WorkflowHandoff> handoffs(String runId) {
    return records.findHandoffs(runId).stream()
        .map(
            row ->
                new WorkflowHandoff(
                    row.schemaVersion(),
                    UUID.fromString(row.senderAttempt()),
                    UUID.fromString(row.senderNode()),
                    UUID.fromString(row.senderAgent()),
                    UUID.fromString(row.dependencyId()),
                    UUID.fromString(row.receiverAttempt()),
                    UUID.fromString(row.receiverNode()),
                    UUID.fromString(row.receiverAgent()),
                    Instant.parse(row.acceptedAt()),
                    row.sourceRunId() == null ? null : UUID.fromString(row.sourceRunId()),
                    row.sourceResult(),
                    records.findAcceptedToolEvidence(row.receiverAttempt(), row.dependencyId())))
        .toList();
  }

  private String currentWorkspaceFingerprint(String workspaceId) {
    if (workspaceId == null) return null;
    try {
      return workspaces.verificationFingerprint(workspaceId);
    } catch (RuntimeException e) {
      return null;
    }
  }

  private static UUID nullableUuid(String value) {
    return value == null ? null : UUID.fromString(value);
  }
}
