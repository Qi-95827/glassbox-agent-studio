package dev.workbench.task;

import dev.workbench.domain.RunStatus;
import dev.workbench.framework.workflow.check.StaleEvidenceException;
import dev.workbench.framework.workflow.plan.PlanNode;
import dev.workbench.framework.workflow.plan.PlanNodeKind;
import dev.workbench.persistence.repository.RunRepository;
import dev.workbench.persistence.repository.WorkflowExecutionRepository;
import dev.workbench.planning.PlanService;
import dev.workbench.store.RunStateStore;
import dev.workbench.store.TaskRecordService;
import dev.workbench.workspace.WorkspaceService;
import java.util.List;
import java.util.UUID;

/**
 * 运行级守卫：运行行锁与计划版本复核、系统检查证据预检、工作区指纹守卫。
 *
 * <p>Git 指纹读取放在短事务之外；短事务内只做数据库复核，只有预检内容仍一致时才允许认领。
 */
final class WorkflowRunGuard {
  private final RunStateStore runs;
  private final TaskRecordService tasks;
  private final WorkflowExecutionRepository records;
  private final PlanService plans;
  private final WorkflowRecoveryService recovery;
  private final RunRepository runRows;
  private final WorkspaceService workspaces;
  private final WorkflowInputProvider inputs;

  WorkflowRunGuard(
      RunStateStore runs,
      TaskRecordService tasks,
      WorkflowExecutionRepository records,
      PlanService plans,
      WorkflowRecoveryService recovery,
      RunRepository runRows,
      WorkspaceService workspaces,
      WorkflowInputProvider inputs) {
    this.runs = runs;
    this.tasks = tasks;
    this.records = records;
    this.plans = plans;
    this.recovery = recovery;
    this.runRows = runRows;
    this.workspaces = workspaces;
    this.inputs = inputs;
  }

  /** 锁定运行行并复核运行状态、有效计划版本与任务授权；返回已校验的计划节点。 */
  List<PlanNode> lock(String runId, UUID version) {
    if (!runRows.lockForUpdate(runId)) throw new IllegalArgumentException("运行不存在。");
    var run = runs.runSnapshot(runId);
    var plan = plans.workflowPlan(runId);
    if (run.status() != RunStatus.RUNNING
        || plan == null
        || !plan.id().equals(version)
        || plan.approvedAt() == null) throw new IllegalStateException("运行未执行，或有效计划尚未确认。");
    var nodes = plans.checkedWorkflowNodes(runId, plan);
    plan.requireTaskAuthorization(tasks.taskDefinition(run.taskId().toString()));
    recovery.validateReuse(runId, plan, nodes);
    return nodes;
  }

  /**
   * 事务外预检：读取直接依赖和检查证据，并计算当前 Git 指纹。
   *
   * <p>Git 调用不持有运行行锁；随后短事务内重新读取依赖与证据，只有预检内容仍一致时才认领。
   */
  CheckEvidencePreflight prepareCheckEvidence(String runId, UUID nodeId) {
    var upstream = inputs.results(runId, nodeId);
    if (upstream.stream().noneMatch(input -> input.kind() == PlanNodeKind.SYSTEM_CHECK))
      return null;
    var task = tasks.taskDefinition(runs.runSnapshot(runId).taskId().toString());
    var dependencies = upstream.stream().map(input -> input.dependencyNodeId().toString()).toList();
    var evidence = new java.util.ArrayList<EvidenceSnapshot>();
    for (var input : upstream) {
      if (input.kind() != PlanNodeKind.SYSTEM_CHECK || input.checkId() == null) continue;
      String checkId = input.checkId().toString();
      String external = records.findExternalReference(checkId);
      if (external == null || external.isBlank()) continue;
      var item =
          records
              .findExternalEvidence(external)
              .orElseThrow(() -> new StaleEvidenceException("系统检查的外部证据已缺失，不能继续依赖。"));
      if (!java.util.Objects.equals(task.bugId(), item.bugId())
          || !java.util.Objects.equals(task.workspaceId(), item.workspaceId()))
        throw new StaleEvidenceException("系统检查证据的 Bug 或工作区归属与当前任务不一致。");
      evidence.add(
          new EvidenceSnapshot(
              checkId,
              external,
              item.bugId(),
              item.workspaceId(),
              item.workspaceFingerprint(),
              input.sourceRunId().toString()));
    }
    // 证据比对和守卫基线必须共用同一次读取：先读当前指纹，再逐项比对，最后把同一值交给守卫。
    // 否则“比对通过后、守卫建立前”的外部修改会被新读取值覆盖，下游会带着旧证据继续执行。
    WorkflowExecutionService.WorkspaceGuard guard = null;
    if (!evidence.isEmpty()) {
      String verified = currentFingerprint(task.workspaceId());
      for (var item : evidence) {
        if (!java.util.Objects.equals(verified, item.fingerprint()))
          throw new StaleEvidenceException("系统检查后工作区已变化，旧检查证据不能继续放行下游节点。");
      }
      guard = new WorkflowExecutionService.WorkspaceGuard(task.workspaceId(), verified);
    }
    return new CheckEvidencePreflight(
        task.bugId(), task.workspaceId(), dependencies, List.copyOf(evidence), guard);
  }

  /**
   * 认领后、节点实际执行前的最后一道工作区版本检查。
   *
   * <p>该检查在事务外执行，覆盖“指纹预检后、模型/工具/容器启动前”的外部编辑窗口。
   */
  void requireWorkspaceCurrent(WorkflowExecutionService.WorkspaceGuard guard) {
    if (guard == null) return;
    if (!java.util.Objects.equals(guard.fingerprint(), currentFingerprint(guard.workspaceId())))
      throw new StaleEvidenceException("节点认领后工作区已变化，检查证据已失效，不能启动下游执行。");
  }

  /** 短事务内只做数据库复核；不再调用 Git，也不重新计算外部指纹。 */
  void verifyCheckEvidence(
      CheckEvidencePreflight preflight,
      List<WorkflowExecutionService.WorkflowInput> upstream) {
    if (preflight == null) return;
    var dependencies = upstream.stream().map(input -> input.dependencyNodeId().toString()).toList();
    if (!dependencies.equals(preflight.dependencies()))
      throw new StaleEvidenceException("节点直接依赖在预检后发生变化，请重新认领。");
    for (var expected : preflight.evidence()) {
      var input =
          upstream.stream()
              .filter(item -> expected.checkId().equals(WorkflowInputProvider.text(item.checkId())))
              .findFirst()
              .orElseThrow(() -> new StaleEvidenceException("预检的检查依赖已不在当前交接中。"));
      if (!expected.sourceRunId().equals(input.sourceRunId().toString()))
        throw new StaleEvidenceException("检查来源运行在预检后发生变化。");
      String external = records.findExternalReference(expected.checkId());
      if (!java.util.Objects.equals(external, expected.externalId()))
        throw new StaleEvidenceException("检查外部证据引用在预检后发生变化。");
      var current =
          records
              .findExternalEvidence(external)
              .orElseThrow(() -> new StaleEvidenceException("系统检查的外部证据已缺失，不能继续依赖。"));
      if (!java.util.Objects.equals(preflight.bugId(), current.bugId())
          || !java.util.Objects.equals(preflight.workspaceId(), current.workspaceId())
          || !java.util.Objects.equals(expected.bugId(), current.bugId())
          || !java.util.Objects.equals(expected.workspaceId(), current.workspaceId())
          || !java.util.Objects.equals(expected.fingerprint(), current.workspaceFingerprint()))
        throw new StaleEvidenceException("系统检查证据在预检后发生变化，不能继续认领。");
    }
  }

  private String currentFingerprint(String workspaceId) {
    try {
      return workspaces.verificationFingerprint(workspaceId);
    } catch (RuntimeException e) {
      throw new StaleEvidenceException("无法确认当前工作区版本，不能继续使用该检查证据。");
    }
  }

  /** 预检时冻结的单条外部证据快照。 */
  record EvidenceSnapshot(
      String checkId,
      String externalId,
      String bugId,
      String workspaceId,
      String fingerprint,
      String sourceRunId) {}

  /** 一次预检结果：任务归属、直接依赖、外部证据与工作区守卫基线。 */
  record CheckEvidencePreflight(
      String bugId,
      String workspaceId,
      List<String> dependencies,
      List<EvidenceSnapshot> evidence,
      WorkflowExecutionService.WorkspaceGuard guard) {}
}
