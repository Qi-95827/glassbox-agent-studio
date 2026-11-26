package dev.workbench.planning;

import dev.workbench.domain.EventType;
import dev.workbench.domain.ModelMode;
import dev.workbench.domain.RunSnapshot;
import dev.workbench.domain.Scenario;
import dev.workbench.domain.TaskDefinition;
import dev.workbench.domain.TaskEntryKind;
import dev.workbench.framework.workflow.plan.CandidateRunPlan;
import dev.workbench.framework.workflow.plan.PlanNode;
import dev.workbench.framework.workflow.plan.PlanNodeSpec;
import dev.workbench.framework.workflow.plan.PlanningRunReader;
import dev.workbench.framework.workflow.plan.RevisionEdge;
import dev.workbench.modelconnection.ModelConnectionService;
import dev.workbench.persistence.entity.RunForkEntity;
import dev.workbench.persistence.repository.RunForkRepository;
import dev.workbench.store.RunEvidenceRecordService;
import dev.workbench.workspace.WorkspaceAuthorizationService;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 从历史运行分叉出新的分支运行：What-If 时间旅行的唯一写入口。
 *
 * <p>分叉不是「就地重跑」，也不是复制证据：它按源运行已落库的计划重建一份受控图，走与候选计划确认 完全相同的物化路径（工作区授权复验、任务与首次运行一并落库、提交后才派发），并把来源与覆盖项 单独记在 {@code run_forks}。源运行的节点、模型调用、工具调用与检查点一行都不改动。
 *
 * <p>明确的边界：分叉复制节点定义引用、依赖、读写范围、产出与验收条件，但<b>不复制</b>节点级 固定模型策略（节点统一沿用任务模型），也不重放上游交接载荷——这两项都需要在协议里新增字段， 当前不做。
 */
@Service
public class RunForkService {
  /** 只有已经结束的运行才能分叉：运行中的工作区仍在被写，分叉会读到半成品状态。 */
  private static final Set<String> TERMINAL =
      Set.of("COMPLETED", "FAILED", "BLOCKED", "CANCELLED", "INTERRUPTED", "LIMITED");

  private static final int MAX_ADDENDUM = 2000;

  /**
   * 分叉请求。
   *
   * @param sourceNodeKey 从哪个节点之后分叉；为空表示从整张图重跑。
   * @param promptAddendum 追加到该节点验收条件与任务约束上的补充说明；为空表示不改提示词。
   * @param modelMode 覆盖本次分支运行的模型模式（mock/deepseek/user）；为空沿用源任务。
   * @param modelConnectionId 覆盖自带 Key 连接；仅 {@code user} 模式使用。
   */
  public record Request(
      String sourceNodeKey, String checkpointId, String promptAddendum, String modelMode,
      String modelConnectionId) {}

  public record Forked(String runId, String taskId, String workspaceId, String parentRunId) {}

  /** 分支运行来源视图；只读投影，不含被覆盖后的正文。 */
  public record ForkSource(
      String runId,
      String parentRunId,
      String sourceNodeKey,
      String checkpointId,
      String promptAddendum,
      String modelMode,
      String modelConnectionId,
      String createdAt) {}

  public record ForkList(String runId, List<ForkSource> forks) {}

  private final PlanningRunReader records;
  private final PlanService plans;
  private final WorkspaceAuthorizationService workspaceAuthorization;
  private final CandidateRunPersister taskCreations;
  private final CandidateRunDispatcher runs;
  private final ModelConnectionService connections;
  private final RunEvidenceRecordService evidence;
  private final RunForkRepository forks;

  public RunForkService(
      PlanningRunReader records,
      PlanService plans,
      WorkspaceAuthorizationService workspaceAuthorization,
      CandidateRunPersister taskCreations,
      CandidateRunDispatcher runs,
      ModelConnectionService connections,
      RunEvidenceRecordService evidence,
      RunForkRepository forks) {
    this.records = records;
    this.plans = plans;
    this.workspaceAuthorization = workspaceAuthorization;
    this.taskCreations = taskCreations;
    this.runs = runs;
    this.connections = connections;
    this.evidence = evidence;
    this.forks = forks;
  }

  public Forked fork(String sourceRunId, Request request) {
    RunSnapshot source = records.runSnapshot(sourceRunId);
    if (!TERMINAL.contains(source.status().name()))
      throw new IllegalStateException(
          "只有已结束的运行才能分叉，当前状态为 " + source.status().name() + "。");
    TaskDefinition task = records.taskDefinition(source.taskId().toString());
    if (task.workspaceId() == null) throw new IllegalStateException("该运行没有工作区，无法分叉。");
    if (task.fileScopes().isEmpty()) throw new IllegalStateException("源任务没有授权文件范围，无法分叉。");

    var request2 = request == null ? new Request(null, null, null, null, null) : request;
    String addendum = addendum(request2.promptAddendum());
    // 先复验工作区授权，再复制图：授权已经不覆盖冻结范围时不必继续算一份跑不起来的计划。
    var prepared = prepare(task);
    String nodeKey = nodeKey(sourceRunId, request2.sourceNodeKey());
    var plan = plan(sourceRunId, nodeKey, addendum);
    ModelMode mode = mode(task, request2);
    String connectionId = connection(mode, request2.modelConnectionId());

    String taskId = UUID.randomUUID().toString();
    String runId = UUID.randomUUID().toString();
    var forkTask = taskDefinition(task, UUID.fromString(taskId), mode, connectionId, addendum);
    String message =
        "从运行 " + sourceRunId + " 分叉" + (nodeKey == null ? "整图重跑" : "（源节点 " + nodeKey + "）") + "。";
    taskCreations.persistFromCandidate(
        forkTask,
        UUID.fromString(runId),
        PlanCandidateConfirmationService.defaultRunLimits(),
        message,
        plan,
        prepared,
        null);
    forks.save(
        new RunForkEntity(
            runId,
            sourceRunId,
            nodeKey,
            blank(request2.checkpointId()),
            addendum,
            mode.code(),
            connectionId,
            Instant.now().toString()));
    evidence.event(runId, EventType.FORK_STARTED, message);
    // 提交后才派发；派发失败不会留下已派发却没有计划或授权的运行。
    runs.dispatchConfirmed(runId);
    return new Forked(runId, taskId, task.workspaceId(), sourceRunId);
  }

  public ForkList forks(String sourceRunId) {
    records.runSnapshot(sourceRunId);
    return new ForkList(
        sourceRunId,
        forks.findByParentRunIdOrderByCreatedAt(sourceRunId).stream()
            .map(
                row ->
                    new ForkSource(
                        row.getRunId(),
                        row.getParentRunId(),
                        row.getSourceNodeKey(),
                        row.getCheckpointId(),
                        row.getPromptAddendum(),
                        row.getModelMode(),
                        row.getModelConnectionId(),
                        row.getCreatedAt()))
            .toList());
  }

  /**
   * 按源运行已落库的计划重建受控图。
   *
   * <p>节点顺序、依赖、读写范围、产出与验收条件全部原样复制；只有被点名的节点会追加一条补充要求。 缺规格或缺定义引用的源计划直接拒绝，而不是复制一份跑不起来的图。
   */
  private CandidateRunPlan plan(String sourceRunId, String nodeKey, String addendum) {
    List<PlanNode> nodes = plans.find(sourceRunId);
    if (nodes.isEmpty()) throw new IllegalStateException("源运行没有可复制的计划节点，无法分叉。");
    Map<UUID, PlanNodeSpec> specs = plans.specsByNode(sourceRunId);
    Map<UUID, String> keysById = new LinkedHashMap<>();
    for (PlanNode node : nodes) keysById.put(node.id(), node.key());
    var planNodes = new ArrayList<CandidateRunPlan.Node>();
    for (PlanNode node : nodes) {
      PlanNodeSpec spec = specs.get(node.id());
      if (spec == null) throw new IllegalStateException("源计划节点 " + node.key() + " 缺少规格，无法安全复制。");
      if (node.definitionRef() == null)
        throw new IllegalStateException("源计划节点 " + node.key() + " 缺少定义引用，无法安全复制。");
      var dependencies = new ArrayList<String>();
      for (UUID dependency : node.dependencyIds()) {
        String key = keysById.get(dependency);
        if (key == null)
          throw new IllegalStateException("源计划节点 " + node.key() + " 的依赖不在同一计划内。");
        dependencies.add(key);
      }
      var acceptance = new ArrayList<>(spec.acceptanceCriteria());
      if (addendum != null && node.key().equals(nodeKey)) acceptance.add("分叉补充要求：" + addendum);
      planNodes.add(
          new CandidateRunPlan.Node(
              node.key(),
              node.label(),
              node.phase(),
              node.definitionRef(),
              dependencies,
              spec.readFiles(),
              spec.writeFiles(),
              spec.outputs(),
              acceptance,
              null));
    }
    return new CandidateRunPlan(1, digest(planNodes), planNodes, edges(sourceRunId, keysById));
  }

  /** 反思回边按节点键重新指向新图；指向不存在节点的历史回边不再复制。 */
  private List<RevisionEdge> edges(String sourceRunId, Map<UUID, String> keysById) {
    var result = new ArrayList<RevisionEdge>();
    for (var edge : plans.revisionEdges(sourceRunId)) {
      String reviewer = keysById.get(edge.reviewerNodeId());
      String target = keysById.get(edge.targetNodeId());
      if (reviewer == null || target == null) continue;
      result.add(new RevisionEdge(reviewer, target, edge.maxRevisions()));
    }
    return List.copyOf(result);
  }

  /** 图摘要必须由受控图内容决定，保证同一份分叉图在任一次重建后摘要一致。 */
  private static String digest(List<CandidateRunPlan.Node> nodes) {
    var digest = new CanonicalDigest("run-fork-plan-v1");
    digest.field(nodes.size());
    for (var node : nodes) {
      digest.field(node.key());
      digest.field(node.title());
      digest.field(node.phase());
      digest.field(node.definition().id());
      digest.field(node.definition().version());
      digest.field(node.dependsOn().size());
      for (String dependency : node.dependsOn()) digest.field(dependency);
      digest.field(node.readFiles().size());
      for (String path : node.readFiles()) digest.field(path);
      digest.field(node.writeFiles().size());
      for (String path : node.writeFiles()) digest.field(path);
      digest.field(node.outputs().size());
      for (var output : node.outputs())
        digest
            .field(output.kind().name())
            .field(output.path())
            .field(String.valueOf(output.required()));
      digest.field(node.acceptanceCriteria().size());
      for (String item : node.acceptanceCriteria()) digest.field(item);
    }
    return digest.hex();
  }

  /** 工作区授权按当前规则复验；与源任务冻结范围不一致时拒绝分叉。 */
  private WorkspaceAuthorizationService.Prepared prepare(TaskDefinition task) {
    var prepared =
        workspaceAuthorization.prepare(
            task.workspaceId(), task.fileScopes(), List.of(), List.of());
    if (!new HashSet<>(prepared.effectiveFiles()).equals(new HashSet<>(task.fileScopes())))
      throw new IllegalStateException("工作区授权已变化，请重新授权后再分叉。");
    return prepared;
  }

  private ModelMode mode(TaskDefinition task, Request request) {
    if (request.modelMode() == null || request.modelMode().isBlank()) return task.modelMode();
    return ModelMode.fromCode(request.modelMode().strip());
  }

  /** 覆盖模型时按与候选确认相同的规则校验：自带 Key 必须已启用且属于当前用户。 */
  private String connection(ModelMode mode, String requested) {
    if (mode != ModelMode.USER) return null;
    String connectionId =
        requested == null || requested.isBlank()
            ? connections
                .defaultConnectionId()
                .orElseThrow(
                    () -> new IllegalArgumentException("请先在「设置 → 模型与 API Key」配置并启用模型连接。"))
            : connections.requireEnabledOwned(requested);
    connections.requireLegacyKeyAccess();
    return connectionId;
  }

  private String nodeKey(String sourceRunId, String requested) {
    if (requested == null || requested.isBlank()) return null;
    String key = requested.strip();
    boolean exists = plans.find(sourceRunId).stream().anyMatch(node -> key.equals(node.key()));
    if (!exists) throw new IllegalArgumentException("源运行没有节点 " + key + "，无法从该处分叉。");
    return key;
  }

  private static String addendum(String value) {
    if (value == null) return null;
    String text = value.strip();
    if (text.isEmpty()) return null;
    if (text.length() > MAX_ADDENDUM)
      throw new IllegalArgumentException("补充说明最多 " + MAX_ADDENDUM + " 字。");
    return text;
  }

  private static String blank(String value) {
    return value == null || value.isBlank() ? null : value.strip();
  }  /** 分叉任务与候选确认使用同一套字段语义，约束里保留源任务约束并追加分叉补充。 */
  private TaskDefinition taskDefinition(
      TaskDefinition source, UUID taskId, ModelMode mode, String connectionId, String addendum) {
    var constraints = new ArrayList<>(source.constraints());
    if (addendum != null) constraints.add("分叉补充：" + addendum);
    return new TaskDefinition(
        taskId,
        source.goal(),
        Scenario.NORMAL,
        mode,
        source.workspaceId(),
        source.fileScopes().getFirst(),
        source.writeEnabled(),
        null,
        false,
        constraints,
        source.acceptanceCriteria(),
        TaskEntryKind.CODE,
        source.fileScopes(),
        Map.of(),
        Map.of(),
        connectionId,
        source.locale() == null
            ? dev.workbench.framework.prompt.PromptResourceLoader.ZH_CN
            : source.locale());
  }
}
