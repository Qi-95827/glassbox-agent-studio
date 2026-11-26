package dev.workbench.orchestration;

import dev.workbench.domain.EventType;
import dev.workbench.domain.ModelMode;
import dev.workbench.domain.RunSnapshot;
import dev.workbench.domain.TaskDefinition;
import dev.workbench.framework.trace.SensitiveDataMasker;
import dev.workbench.framework.workflow.plan.CandidateRunPlan;
import dev.workbench.framework.workflow.plan.WorkflowTemplate;
import dev.workbench.modelconnection.ModelConnectionService;
import dev.workbench.persistence.repository.RunData;
import dev.workbench.persistence.repository.RunRepository;
import dev.workbench.persistence.repository.TaskRepository;
import dev.workbench.planning.CandidatePlanCopyService;
import dev.workbench.planning.CandidateRunDispatcher;
import dev.workbench.planning.PlanService;
import dev.workbench.store.RunCreationStore;
import dev.workbench.store.RunEvidenceRecordService;
import dev.workbench.store.RunIterationRecordService;
import dev.workbench.store.TaskRecordService;
import dev.workbench.task.CostService;
import dev.workbench.workspace.AuthorizedFileSet;
import dev.workbench.workspace.WorkspaceAuthorizationService;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 交付后的同任务多轮调改迭代：唯一写入口。
 *
 * <p>迭代不是「就地重跑」，也不是复制证据：它始终在<b>同一个 taskId</b> 下新建一轮运行（{@code parent_id} 指向源运行），
 * 按源运行已落库的受控图或任务既有执行路径重建计划，并把用户本轮调改意见与上一轮交付摘要写入本轮上下文、验收条件与
 * 审计事件。源运行的节点、模型 I/O、工具轨迹、检查点与交付记录一行都不改动。
 *
 * <p>安全边界与分叉完全一致：工作区授权按当前规则复验（整仓 {@code .} 范围走目录复验，不会被手工选择上限误杀），
 * 宿主机全局 DeepSeek 密钥只有管理员可用，普通用户迭代只能走自己的 BYOK 连接。
 */
@Service
public class RunIterationService {
  /** 只有已经结束的运行才能迭代：运行中的工作区仍在被写，新一轮会读到半成品状态。 */
  private static final Set<String> TERMINAL =
      Set.of("COMPLETED", "FAILED", "BLOCKED", "CANCELLED", "INTERRUPTED", "LIMITED");
  /** 同一任务下不允许并行的第二轮：连点会在这里被拒绝。 */
  private static final Set<String> ACTIVE = Set.of("PENDING", "RUNNING", "PAUSED");

  private static final int MAX_FEEDBACK = 2000;
  private static final int MAX_PREVIOUS_SUMMARY = 1200;
  private static final int MAX_EVENT_MESSAGE = 1900;

  /**
   * 迭代请求。
   *
   * @param feedback 本轮调改意见，必填且最多 {@value #MAX_FEEDBACK} 字；落库与注入提示词前统一脱敏。
   * @param modelMode 覆盖本轮模型模式（mock/deepseek/user）；为空沿用任务当前模式。
   * @param modelConnectionId 覆盖自带 Key 连接；仅 {@code user} 模式使用。
   * @param targetNodeKey 可选的聚焦节点；给出时本轮调改要求只加到该节点验收条件上。
   */
  public record Request(
      String feedback, String modelMode, String modelConnectionId, String targetNodeKey) {}

  public record Iterated(String runId, String taskId, int roundIndex, String parentRunId) {}

  private final TaskRecordService tasks;
  private final RunRepository runRows;
  private final TaskRepository taskRows;
  private final PlanService plans;
  private final CandidatePlanCopyService planCopies;
  private final WorkspaceAuthorizationService workspaceAuthorization;
  private final RunCreationStore runCreation;
  private final CandidateRunDispatcher runs;
  private final ModelConnectionService connections;
  private final RunEvidenceRecordService evidence;
  private final RunIterationRecordService iterations;
  private final CostService costs;
  private final TransactionTemplate tx;

  public RunIterationService(
      TaskRecordService tasks,
      RunRepository runRows,
      TaskRepository taskRows,
      PlanService plans,
      CandidatePlanCopyService planCopies,
      WorkspaceAuthorizationService workspaceAuthorization,
      RunCreationStore runCreation,
      CandidateRunDispatcher runs,
      ModelConnectionService connections,
      RunEvidenceRecordService evidence,
      RunIterationRecordService iterations,
      CostService costs,
      TransactionTemplate tx) {
    this.tasks = tasks;
    this.runRows = runRows;
    this.taskRows = taskRows;
    this.plans = plans;
    this.planCopies = planCopies;
    this.workspaceAuthorization = workspaceAuthorization;
    this.runCreation = runCreation;
    this.runs = runs;
    this.connections = connections;
    this.evidence = evidence;
    this.iterations = iterations;
    this.costs = costs;
    this.tx = tx;
  }

  public Iterated iterate(String sourceRunId, Request request) {
    Request input = request == null ? new Request(null, null, null, null) : request;
    RunData source =
        runRows.findRunById(sourceRunId).orElseThrow(() -> new EmptyResultDataAccessException(1));
    if (!TERMINAL.contains(source.status()))
      throw new IllegalStateException("只有已结束的运行才能发起调改迭代，当前状态为 " + source.status() + "。");
    String taskId = source.taskId();
    TaskDefinition task = tasks.taskDefinition(taskId);
    String feedback = feedback(input.feedback());
    var snapshot = source.snapshot();
    ModelMode mode = mode(task, input);
    String connectionId = connection(mode, input.modelConnectionId());
    String targetNodeKey = nodeKey(sourceRunId, input.targetNodeKey());
    // 先复验工作区授权与模型权限，再复制图：权限不满足时不必算一份跑不起来的计划。
    if (task.workspaceId() != null) verifyWorkspace(task);
    String previousSummary = previousSummary(source);

    List<RunIterationRecordService.Note> history = iterations.notes(taskId);
    int roundIndex = history.size() + 2;
    var choice = planChoice(sourceRunId, task, feedback, targetNodeKey, history, roundIndex);
    String runId = UUID.randomUUID().toString();
    String message =
        "同任务第 "
            + roundIndex
            + " 轮调改"
            + (targetNodeKey == null ? "" : "（聚焦节点 " + targetNodeKey + "）")
            + "："
            + feedback;

    tx.executeWithoutResult(
        status -> {
          // 锁住任务行：同一任务的并发迭代请求在这里串行化，只允许一轮处于运行中。
          if (!taskRows.lockForUpdate(taskId)) throw new EmptyResultDataAccessException(1);
          for (RunData existing : runRows.findByTaskId(taskId))
            if (ACTIVE.contains(existing.status()))
              throw new IllegalStateException(
                  "该任务已有进行中的运行（" + existing.id() + "），请等待其结束后再发起下一轮调改。");
          persistingCreate(runId, taskId, sourceRunId, snapshot, task, choice);
          iterations.record(
              runId,
              taskId,
              sourceRunId,
              roundIndex,
              targetNodeKey,
              mode.code(),
              connectionId,
              feedback,
              previousSummary,
              java.time.Instant.now().toString());
          evidence.event(runId, EventType.RUN_ITERATION_STARTED, limited(message));
          // 平台费用硬预算沿用源运行剩余额度；自带 Key 使用用户自己的额度，没有平台预算行。
          costs.inheritLimit(sourceRunId, runId);
        });
    // 事务提交后才派发；派发失败不会留下已派发却没有计划或授权的运行。
    runs.dispatchConfirmed(runId);
    return new Iterated(runId, taskId, roundIndex, sourceRunId);
  }

  /** 三种受控图来源：可复制的候选图、同一动态模板、任务既有固定计划。 */
  private record PlanChoice(CandidateRunPlan plan, WorkflowTemplate template, boolean legacy) {}

  private PlanChoice planChoice(
      String sourceRunId,
      TaskDefinition task,
      String feedback,
      String targetNodeKey,
      List<RunIterationRecordService.Note> history,
      int roundIndex) {
    String base =
        "同任务第 "
            + roundIndex
            + " 轮调改要求（用户在上轮交付后提出，必须逐条满足）："
            + feedback;
    String previous = history.isEmpty() ? null : history.getLast().feedback();
    // 上一轮的调改要点同样进入本轮约束，避免修好一处又回退另一处。
    String requirement =
        previous == null || previous.isBlank()
            ? base
            : base + "\n上上轮调改要点（避免回退）：" + limited(previous);
    if (planCopies.copyable(sourceRunId))
      return new PlanChoice(
          planCopies.copy(
              sourceRunId,
              (node, spec) -> acceptance(spec.acceptanceCriteria(), node.key(), targetNodeKey, requirement)),
          null,
          false);
    if (plans.workflowPlan(sourceRunId) != null)
      return new PlanChoice(null, plans.templateOf(sourceRunId), false);
    return new PlanChoice(null, null, true);
  }

  /** 聚焦节点只改自己的验收条件；未指定时整张图的每个节点都带上本轮共同要求。 */
  private static List<String> acceptance(
      List<String> existing, String nodeKey, String targetNodeKey, String requirement) {
    var result = new ArrayList<>(existing);
    if (targetNodeKey == null || targetNodeKey.equals(nodeKey)) result.add(requirement);
    return List.copyOf(result);
  }

  private void persistingCreate(
      String runId,
      String taskId,
      String sourceRunId,
      RunSnapshot snapshot,
      TaskDefinition task,
      PlanChoice choice) {
    if (choice.plan() != null) {
      runCreation.createIterationRunFromPlan(runId, taskId, sourceRunId, choice.plan(), snapshot);
      return;
    }
    if (choice.template() != null) {
      runCreation.createIterationRunFromTemplate(
          runId, taskId, sourceRunId, choice.template(), snapshot);
      return;
    }
    runCreation.createIterationRunFromLegacyPlan(
        runId, taskId, sourceRunId, snapshot, task.writeEnabled());
  }

  /** 工作区授权按当前规则复验；整仓范围走目录复验，与分叉共用同一套判定。 */
  private void verifyWorkspace(TaskDefinition task) {
    var scopes = task.fileScopes();
    if (scopes.isEmpty())
      throw new IllegalStateException("源任务没有授权文件范围，无法开启下一轮调改。");
    if (scopes.size() > AuthorizedFileSet.MAX_EXPLICIT) {
      workspaceAuthorization.reverifyDirectories(
          task.workspaceId(), List.of("."), List.of(), scopes);
      return;
    }
    var prepared =
        workspaceAuthorization.prepare(task.workspaceId(), scopes, List.of(), List.of());
    if (!new HashSet<>(prepared.effectiveFiles()).equals(new HashSet<>(scopes)))
      throw new IllegalStateException("工作区授权已变化，请重新授权后再发起下一轮调改。");
  }

  private ModelMode mode(TaskDefinition task, Request request) {
    if (request.modelMode() == null || request.modelMode().isBlank()) return task.modelMode();
    return ModelMode.fromCode(request.modelMode().strip());
  }

  /**
   * 模型覆盖的权限校验。
   *
   * <p>两种模式的特权完全不同：宿主机全局 DeepSeek 密钥是平台特权，只有 {@code DEEPSEEK} 才需要；自带 Key
   * 走的是用户自己的额度与已启用连接，普通用户即可，不能误加平台特权校验。
   */
  private String connection(ModelMode mode, String requested) {
    if (mode == ModelMode.DEEPSEEK) {
      connections.requireLegacyKeyAccess();
      return null;
    }
    if (mode != ModelMode.USER) return null;
    return requested == null || requested.isBlank()
        ? connections
            .defaultConnectionId()
            .orElseThrow(
                () -> new IllegalArgumentException("请先在「设置 → 模型与 API Key」配置并启用模型连接。"))
        : connections.requireEnabledOwned(requested);
  }

  private String nodeKey(String sourceRunId, String requested) {
    if (requested == null || requested.isBlank()) return null;
    String key = requested.strip();
    boolean exists = plans.find(sourceRunId).stream().anyMatch(node -> key.equals(node.key()));
    if (!exists) throw new IllegalArgumentException("源运行没有节点 " + key + "，无法在该节点调改。");
    return key;
  }

  /** 调改意见必填并限长；脱敏后再落库，避免凭据进入提示词与审计记录。 */
  private static String feedback(String raw) {
    if (raw == null || raw.isBlank())
      throw new IllegalArgumentException("请填写调改意见（1 至 " + MAX_FEEDBACK + " 字）。");
    String text = raw.strip();
    if (text.length() > MAX_FEEDBACK)
      throw new IllegalArgumentException("调改意见最多 " + MAX_FEEDBACK + " 字，当前 " + text.length() + " 字。");
    return SensitiveDataMasker.maskAndTruncate(text, MAX_FEEDBACK).text();
  }

  /** 上一轮交付结论摘要：优先交付正文，其次是停止原因；脱敏并截断后注入本轮约束。 */
  private static String previousSummary(RunData source) {
    String text =
        source.result() != null && !source.result().isBlank() ? source.result() : source.reason();
    if (text == null || text.isBlank()) return "上一轮没有可用的交付结论，本轮只依据用户调改意见。";
    return SensitiveDataMasker.maskAndTruncate(text, MAX_PREVIOUS_SUMMARY).text();
  }

  private static String limited(String value) {
    if (value == null) return "";
    return value.length() <= MAX_EVENT_MESSAGE ? value : value.substring(0, MAX_EVENT_MESSAGE - 1) + "…";
  }
}
