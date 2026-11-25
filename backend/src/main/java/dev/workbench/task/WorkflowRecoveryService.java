package dev.workbench.task;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import dev.workbench.domain.EventType;
import dev.workbench.domain.RunSnapshot;
import dev.workbench.domain.RunStatus;
import dev.workbench.domain.ToolKind;
import dev.workbench.framework.tool.ResourceVersion;
import dev.workbench.framework.workflow.check.SystemCheckStatus;
import dev.workbench.framework.workflow.plan.PlanNode;
import dev.workbench.framework.workflow.plan.PlanNodeKind;
import dev.workbench.framework.workflow.plan.PlanNodeStatus;
import dev.workbench.planning.PlanService;
import dev.workbench.framework.workflow.plan.WorkflowCheckpoint;
import dev.workbench.framework.workflow.plan.WorkflowPlan;
import dev.workbench.framework.workflow.plan.WorkflowTemplate;
import dev.workbench.persistence.repository.WorkflowRecoveryEvidenceRepository;
import dev.workbench.persistence.entity.WorkflowCheckpointEntity;
import dev.workbench.persistence.repository.RunRepository;
import dev.workbench.persistence.repository.WorkflowCheckpointRepository;
import dev.workbench.workspace.AuthorizedFileSet;
import dev.workbench.workspace.WorkspaceService;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import dev.workbench.domain.RunLimits;
import dev.workbench.domain.TaskEntryKind;
import dev.workbench.store.RunCreationStore;
import dev.workbench.store.RunEvidenceRecordService;
import dev.workbench.store.RunStateStore;
import dev.workbench.store.TaskRecordService;
import dev.workbench.store.ReviewCompletion;
import dev.workbench.store.StopCompletion;
import dev.workbench.planning.RecoveryLeaseService;
import dev.workbench.planning.RunSnapshotService;

/** 验证纯文本和只读工作区的节点边界并创建恢复运行。*/
@Service
public class WorkflowRecoveryService {
  private final RunStateStore runs;
  private final RunCreationStore creation;
  private final TaskRecordService tasks;
  private final RunEvidenceRecordService runEvents;
  private final PlanService plans;
  private final WorkflowRecoveryEvidenceRepository evidenceStore;
  private final WorkflowCheckpointRepository checkpointRepository;
  private final RunRepository runStore;
  private final TransactionTemplate tx;
  private final ObjectMapper json;
  private final WorkspaceService workspaces;
  private final RunSnapshotService snapshots;
  private final RecoveryLeaseService leases;
  private final CostService costs;

  public WorkflowRecoveryService(
      RunStateStore runs,
      RunCreationStore creation,
      TaskRecordService tasks,
      RunEvidenceRecordService runEvents,
      PlanService plans,
      WorkflowRecoveryEvidenceRepository evidenceStore,
      WorkflowCheckpointRepository checkpointRepository,
      RunRepository runStore,
      TransactionTemplate tx,
      ObjectMapper json,
      WorkspaceService workspaces,
      RunSnapshotService snapshots,
      RecoveryLeaseService leases,
      CostService costs) {
    this.runs = runs;
    this.creation = creation;
    this.tasks = tasks;
    this.runEvents = runEvents;
    this.plans = plans;
    this.evidenceStore = evidenceStore;
    this.checkpointRepository = checkpointRepository;
    this.runStore = runStore;
    this.tx = tx;
    this.json = json;
    this.workspaces = workspaces;
    this.snapshots = snapshots;
    this.leases = leases;
    this.costs = costs;
  }

  /** 调用方持有运行行锁，保存边界时与相应节点结算共用事务。*/
  @Transactional(propagation = Propagation.MANDATORY)
  void saveBoundary(String runId, WorkflowPlan plan, List<PlanNode> nodes, long elapsed) {
    if ((plan.scope() != WorkflowTemplate.Scope.TEXT_ONLY
            && plan.scope() != WorkflowTemplate.Scope.WORKSPACE_READ_ONLY)
        || plan.sourceCheckpointId() != null
        || nodes.stream().allMatch(node -> node.status() == PlanNodeStatus.COMPLETED)) return;
    if (plan.scope() == WorkflowTemplate.Scope.TEXT_ONLY
        && evidenceStore.hasRateLimitAttempt(runId)) {
      runEvents.event(runId, EventType.CHECKPOINT_SKIPPED, "当前恢复策略不覆盖已有重试记录的工作流。");
      return;
    }
    var run = runs.runSnapshot(runId);
    Boundary boundary;
    try {
      boundary = checkedBoundary(run, plan, nodes, run.rounds(), run.tools(), elapsed);
    } catch (UnsafeBoundaryException e) {
      runEvents.event(runId, EventType.CHECKPOINT_SKIPPED, "当前边界不能安全恢复，已跳过检查点：" + e.getMessage());
      return;
    }
    if (checkpointRepository.existsByPlanIdAndCompletedNodes(
        plan.id().toString(), boundary.completedNodes())) {
      return;
    }
    checkpointRepository.saveAndFlush(
        new WorkflowCheckpointEntity(
            plan.id().toString(),
            boundary.completedNodes(),
            run.rounds(),
            boundary.tools(),
            elapsed,
            boundary.sha256(),
            Instant.now().toString()));
    // 同一事务保存周期快照，记录事件位置、累计预算和工作区指纹。
    snapshots.capture(runId, plan, nodes, elapsed);
    runEvents.event(runId, EventType.CHECKPOINT_SAVED, "已保存工作流节点边界。");
  }

  /**
   * 恢复子运行继承的累计消耗；来源缺失时返回 null，不从当前用量倒推。
   *
   * <p>历史 {@code tools} 受纯文本约束恒为 0；只读工作区边界的实际工具次数保存在 {@code tool_calls}。   */
  public InheritedConsumption inherited(UUID checkpointId) {
    return checkpointRepository
        .findById(checkpointId.toString())
        .map(
            row ->
                new InheritedConsumption(row.getRounds(), row.getToolCalls(), row.getElapsedMs()))
        .orElse(null);
  }

  public record InheritedConsumption(int rounds, int tools, long elapsedMs) {}

  public List<WorkflowCheckpoint> checkpoints(String runId, WorkflowPlan plan) {
    if (plan.scope() != WorkflowTemplate.Scope.TEXT_ONLY
        && plan.scope() != WorkflowTemplate.Scope.WORKSPACE_READ_ONLY) return List.of();
    var run = runs.runSnapshot(runId);
    boolean mayRecover =
        run.status() == RunStatus.INTERRUPTED
            && !runs.hasRecovery(runId)
            && plan.sourceCheckpointId() == null;
    return checkpointRepository.findByPlanIdOrderByCompletedNodesAsc(plan.id().toString()).stream()
        .map(
            checkpoint -> {
              UUID checkpointId = UUID.fromString(checkpoint.id());
              return new WorkflowCheckpoint(
                  checkpointId,
                  checkpoint.version(),
                  checkpoint.completedNodes(),
                  checkpoint.rounds(),
                  checkpoint.elapsedMs(),
                  Instant.parse(checkpoint.createdAt()),
                  mayRecover && available(run, plan, checkpointId));
            })
        .toList();
  }

  private boolean available(RunSnapshot run, WorkflowPlan plan, UUID id) {
    try {
      verifyStoredBoundary(run, plan, id);
      return true;
    } catch (IllegalArgumentException | IllegalStateException e) {
      return false;
    }
  }

  public void recover(String runId, UUID checkpointId, UUID nextRunId) {
    var plan = plans.workflowPlan(runId);
    if (plan == null) throw new IllegalStateException("该运行没有动态计划。");
    snapshots.verifyLatest(runId, plan);
    var lease =
        leases.acquire(runId, "local-recovery", java.time.Duration.ofSeconds(60));
    try {
      tx.executeWithoutResult(          ignored -> {
            if (!runStore.lockForUpdate(runId)) throw new IllegalArgumentException("运行不存在。");
            var run = runs.runSnapshot(runId);
            if (run.status() != RunStatus.INTERRUPTED || runs.hasRecovery(runId))
              throw new IllegalStateException("该运行当前无法申请恢复。");
            var stored = verifyStoredBoundary(run, plan, checkpointId);
            creation.createWorkflowRecoveryRun(
                nextRunId,
                run,
                stored.boundary().template(),
                plan.reviewRequired(),
                stored.rounds(),
                stored.tools(),
                stored.elapsedMs());
            costs.inheritLimit(runId, nextRunId.toString());
            var reused = new LinkedHashMap<String, UUID>();
            stored.boundary().sources().forEach((key, source) -> reused.put(key, source.agentId()));
            plans.attachRecovery(nextRunId.toString(), checkpointId, reused, dev.workbench.domain.WorkbenchTime.now());
            runEvents.event(nextRunId.toString(), EventType.RECOVERY_STARTED, "已从动态工作流检查点恢复，保留原运行及累计消耗。");
          });
    } finally {
      // 恢复尝试无论成功或失败都释放租约；重复恢复由父运行已有子运行和状态条件阻止。
      leases.release(runId, lease.token());
    }
  }

  /** 子运行恢复的逻辑节点必须持续对应同一父计划的原始实例。*/
  void validateReuse(String runId, WorkflowPlan plan, List<PlanNode> nodes) {
    if (plan.sourceCheckpointId() == null) {
      if (nodes.stream().anyMatch(node -> node.status() == PlanNodeStatus.REUSED))
        throw new IllegalStateException("此工作流没有经验证的复用来源。");
      return;
    }
    var run = runs.runSnapshot(runId);
    if (run.parentId() == null) throw new IllegalStateException("恢复工作流缺少父运行。");
    var original = runs.runSnapshot(run.parentId().toString());
    if (!original.taskId().equals(run.taskId())) throw new IllegalStateException("复用来源跨越任务边界。");
    var originalPlan = plans.workflowPlan(original.id().toString());
    var saved = verifyStoredBoundary(original, originalPlan, plan.sourceCheckpointId());
    var reused = saved.boundary().sources();
    if (!saved.boundary().template().equals(template(plan, nodes))
        || plan.reviewRequired() != originalPlan.reviewRequired()
        || !limits(run).equals(limits(original))
        || run.rounds() < saved.rounds()
        || (plan.scope() == WorkflowTemplate.Scope.TEXT_ONLY && run.tools() != 0)
        || run.tools() < saved.tools()
        || nodes.stream()
            .anyMatch(
                node -> {
                  var source = reused.get(node.key());
                  return source == null
                      ? node.status() == PlanNodeStatus.REUSED
                      : node.status() != PlanNodeStatus.REUSED
                          || !source.agentId().equals(node.sourceAgentId());
                })) throw new IllegalStateException("恢复计划或历史节点关联已变化。");
  }

  private StoredBoundary verifyStoredBoundary(RunSnapshot run, WorkflowPlan plan, UUID checkpoint) {
    if (plan == null
        || (plan.scope() != WorkflowTemplate.Scope.TEXT_ONLY
            && plan.scope() != WorkflowTemplate.Scope.WORKSPACE_READ_ONLY)
        || plan.sourceCheckpointId() != null
        || run.status() != RunStatus.INTERRUPTED)
      throw new IllegalStateException("当前运行没有可安全恢复的动态检查点。");
    var saved =
        checkpointRepository
            .findByIdAndPlanIdAndVersion(
                checkpoint.toString(),
                plan.id().toString(),
                WorkflowCheckpointEntity.CURRENT_FORMAT_VERSION)
            .orElseThrow(() -> new IllegalArgumentException("检查点不属于该动态计划。"));
    var boundary =
        checkedBoundary(
            run,
            plan,
            plans.checkedWorkflowNodes(run.id().toString(), plan),
            saved.rounds(),
            saved.actualToolCalls(),
            saved.elapsedMs());
    if (saved.completedNodes() != boundary.completedNodes()
        || !saved.boundarySha256().equals(boundary.sha256()))
      throw new IllegalStateException("检查点后的节点、来源或资源版本已变化，无法安全恢复。");
    return new StoredBoundary(saved.rounds(), saved.actualToolCalls(), saved.elapsedMs(), boundary);
  }

  private Boundary checkedBoundary(
      RunSnapshot run,
      WorkflowPlan plan,
      List<PlanNode> nodes,
      int rounds,
      int tools,
      long elapsed) {
    String runId = run.id().toString();
    var task = tasks.taskDefinition(run.taskId().toString());
    try {
      plan.requireTaskAuthorization(task);
    } catch (IllegalStateException e) {
      throw new UnsafeBoundaryException("任务授权与批准的计划不一致，不能复用该边界。");
    }
    if (plan.approvedAt() == null
        || run.rounds() != rounds
        || run.tools() != tools
        || nodes.stream()
            .anyMatch(
                node ->
                    node.status() != PlanNodeStatus.COMPLETED
                        && node.status() != PlanNodeStatus.PENDING
                        && node.status() != PlanNodeStatus.SKIPPED))
      throw new UnsafeBoundaryException("检查点后存在未结算节点，或当前任务配置不支持恢复。");
    boolean workspace = plan.scope() == WorkflowTemplate.Scope.WORKSPACE_READ_ONLY;
    if (workspace) {
      if (task.workspaceId() == null
          || task.writeEnabled()
          || task.bugId() != null
          || task.memoryEnabled()
          || task.entryKind() == dev.workbench.domain.TaskEntryKind.BUG)
        throw new UnsafeBoundaryException("当前任务不是只读工作区分析，不能复用该边界。");
    } else if (task.workspaceId() != null
        || task.bugId() != null
        || task.memoryEnabled()
        || task.writeEnabled()
        || run.tools() != 0) {
      throw new UnsafeBoundaryException("当前任务配置不支持纯文本恢复。");
    }
    var sources = new LinkedHashMap<String, Source>();
    for (var row : evidenceStore.findCompletedSources(runId, plan.id().toString())) {
      sources.put(
          row.nodeKey(),
          new Source(
              UUID.fromString(row.nodeId()), UUID.fromString(row.attemptId()),
              UUID.fromString(row.agentId()), row.result()));
    }
    var usage =
        evidenceStore.findCompletedModelUsage(runId).stream()
            .map(
                row ->
                    new Usage(
                        row.id(),
                        row.agentId(),
                        row.model(),
                        row.input(),
                        row.output(),
                        row.durationMs()))
            .toList();
    long completedAgents =
        nodes.stream()
            .filter(
                node ->
                    node.status() == PlanNodeStatus.COMPLETED && node.kind() == PlanNodeKind.AGENT)
            .count();
    long completedChecks =
        nodes.stream()
            .filter(
                node ->
                    node.status() == PlanNodeStatus.COMPLETED
                        && node.kind() == PlanNodeKind.SYSTEM_CHECK)
            .count();
    if (completedAgents != sources.size()
        || evidenceStore.countAgents(runId) != sources.size()
        || evidenceStore.countModels(runId) != rounds
        || usage.size() != rounds
        || sources.values().stream().anyMatch(source -> source.result().isBlank()))
      throw new UnsafeBoundaryException("检查点后存在未结算调用，或历史节点结果无法验证。");

    List<Evidence> evidence = List.of();
    List<CheckState> checks = List.of();
    WorkspaceService.Workspace workspaceIdentity = null;
    if (workspace) {
      if (evidenceStore.hasRunningToolCall(runId))
        throw new UnsafeBoundaryException("存在未结算的工具调用，不能保存只读工作区边界。");
      evidence = completedEvidence(runId, sources);
      if (evidence.isEmpty() && !sources.isEmpty())
        throw new UnsafeBoundaryException("没有可核对的只读工具版本，不能复用该分析结果。");
      if (!evidence.isEmpty()) verifyCurrentResources(task, evidence);
      checks = completedChecks(runId);
      if (checks.size() != completedChecks)
        throw new UnsafeBoundaryException("已完成的系统检查缺少可验证记录，不能保存只读工作区边界。");
      if (evidenceStore.hasRunningCheck(runId))
        throw new UnsafeBoundaryException("存在未结算的系统检查，不能保存只读工作区边界。");
      try {
        workspaceIdentity = workspaces.get(task.workspaceId());
      } catch (RuntimeException e) {
        throw new UnsafeBoundaryException("无法读取工作区身份，不能保存只读工作区边界。");
      }
    } else {
      if (evidenceStore.hasToolCalls(runId)) throw new UnsafeBoundaryException("纯文本边界不应包含工具调用。");
    }

    int completedNodes =
        (int) nodes.stream().filter(node -> node.status() == PlanNodeStatus.COMPLETED).count();
    String digest =
        workspace
            ? fingerprint(
                List.of(
                    "workflow-checkpoint-v2",
                    plan.id(),
                    plan.graphSha256(),
                    plan.approvedAt().toString(),
                    plan.reviewRequired(),
                    plan.scope().name(),
                    String.valueOf(plan.authorizationSha256()),
                    task,
                    workspaceIdentity.id(),
                    workspaceIdentity.repositoryPath(),
                    workspaceIdentity.baseCommit(),
                    limits(run),
                    rounds,
                    tools,
                    elapsed,
                    completedNodes,
                    sources,
                    evidence,
                    checks,
                    usage))
            : fingerprint(
                List.of(
                    "workflow-checkpoint-v1",
                    plan.id(),
                    plan.graphSha256(),
                    plan.approvedAt().toString(),
                    plan.reviewRequired(),
                    task,
                    limits(run),
                    rounds,
                    elapsed,
                    sources,
                    usage));
    return new Boundary(template(plan, nodes), Map.copyOf(sources), completedNodes, tools, digest);
  }

  /** 已结算 Agent 的只读工具调用及其观察到的文件版本。 */
  private List<Evidence> completedEvidence(String runId, Map<String, Source> sources) {
    var evidence = new java.util.ArrayList<Evidence>();
    for (var row : evidenceStore.findCompletedFileEvidence(runId)) {
      UUID agentId = UUID.fromString(row.agentId());
      boolean reused =
          sources.values().stream().anyMatch(source -> source.agentId().equals(agentId));
      if (!reused) continue;
      if (row.resourceVersion() == null)
        throw new UnsafeBoundaryException("只读工具调用缺少文件版本证据，不能复用该结果。");
      evidence.add(
          new Evidence(UUID.fromString(row.id()), agentId, row.tool(), row.resourceVersion()));
    }
    return List.copyOf(evidence);
  }

  /** 恢复前重新读取授权文件，证明分析时的版本与当前内容一致。*/
  private void verifyCurrentResources(
      dev.workbench.domain.TaskDefinition task, List<Evidence> evidence) {
    var authorized = new AuthorizedFileSet(task.fileScopes());
    for (var item : evidence) {
      Map<String, String> versions;
      try {
        versions = ResourceVersion.decode(item.resourceVersion());
      } catch (IllegalArgumentException e) {
        throw new UnsafeBoundaryException("工具版本证据格式无效，不能复用该结果。");
      }
      for (var entry : versions.entrySet()) {
        String path;
        try {
          path = authorized.require(entry.getKey());
        } catch (IllegalArgumentException e) {
          throw new UnsafeBoundaryException("工具读取的文件已不在当前授权范围，不能复用该结果。");
        }
        String current;
        try {
          current = workspaces.read(task.workspaceId(), path).sha256();
        } catch (RuntimeException e) {
          throw new UnsafeBoundaryException("授权文件已不可读，不能复用该分析结果：" + path);
        }
        if (!current.equals(entry.getValue()))
          throw new UnsafeBoundaryException("授权文件内容已变化，不能复用该分析结果：" + path);
      }
    }
  }

  /** 已完成系统检查必须全部通过；检查结论作为边界来源的一部分。*/
  private List<CheckState> completedChecks(String runId) {
    return evidenceStore.findLatestCompletedNodeChecks(runId).stream()
        .map(
            row -> {
              if (row.status() != SystemCheckStatus.PASSED)
                throw new UnsafeBoundaryException("已完成的系统检查没有通过，不能复用该边界。");
              return new CheckState(
                  row.nodeKey(), row.id(), row.inputsSha256(), row.externalEvidenceId());
            })
        .toList();
  }

  private static RunLimits limits(RunSnapshot run) {
    return new RunLimits(
        run.maxRounds(),
        run.maxTools(),
        run.maxPerTool(),
        run.maxReportedTokens(),
        run.maxIdenticalTools(),
        run.maxSeconds(),
        run.maxAgentRounds(),
        run.maxAgentTools());
  }

  private static WorkflowTemplate template(WorkflowPlan plan, List<PlanNode> nodes) {
    Map<UUID, String> keys = new LinkedHashMap<>();
    for (var node : nodes) keys.put(node.id(), node.key());
    return new WorkflowTemplate(
        plan.templateId(),
        plan.templateVersion(),
        plan.scope(),
        plan.maxAttempts(),
        nodes.stream()
            .map(
                node ->
                    node.systemCheck()
                        ? WorkflowTemplate.Step.systemCheck(
                            node.key(),
                            node.label(),
                            node.phase(),
                            node.checkRef(),
                            node.dependencyIds().stream().map(keys::get).sorted().toList())
                        : new WorkflowTemplate.Step(
                            node.key(),
                            node.label(),
                            node.phase(),
                            node.definitionRef(),
                            node.dependencyIds().stream().map(keys::get).sorted().toList(),
                            node.budget()))
            .toList());
  }

  private String fingerprint(Object snapshot) {
    try {
      byte[] bytes =
          json.writer()
              .with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
              .writeValueAsBytes(snapshot);
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (JsonProcessingException | NoSuchAlgorithmException e) {
      throw new IllegalStateException("无法计算动态检查点摘要。", e);
    }
  }

  private record Source(UUID nodeId, UUID attemptId, UUID agentId, String result) {}

  /** 只读工具调用观察到的版本证据，恢复时必须仍对应当前授权文件。*/
  private record Evidence(UUID toolId, UUID agentId, ToolKind tool, String resourceVersion) {}

  /** 已完成系统检查的结论引用，不复制结果正文。*/
  private record CheckState(
      String nodeKey, String checkId, String inputsSha256, String externalEvidenceId) {}

  private record Usage(
      String id, String agentId, String model, Integer input, Integer output, Long durationMs) {}

  private record Boundary(
      WorkflowTemplate template,
      Map<String, Source> sources,
      int completedNodes,
      int tools,
      String sha256) {}

  private record StoredBoundary(int rounds, int tools, long elapsedMs, Boundary boundary) {}

  private static class UnsafeBoundaryException extends IllegalStateException {
    UnsafeBoundaryException(String message) {
      super(message);
    }
  }
}
