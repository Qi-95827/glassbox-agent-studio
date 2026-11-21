package dev.workbench.planning;

import dev.workbench.domain.ExecutionScope;
import dev.workbench.domain.ExecutionScopeResolver;
import dev.workbench.domain.ModelKind;
import dev.workbench.domain.ModelMode;
import dev.workbench.domain.RunLimits;
import dev.workbench.domain.Scenario;
import dev.workbench.domain.TaskDefinition;
import dev.workbench.domain.TaskEntryKind;
import dev.workbench.framework.workflow.plan.CandidateRunPlan;
import dev.workbench.framework.workflow.plan.PlanningRunReader;
import dev.workbench.persistence.entity.PlanCandidateEntity;
import dev.workbench.workspace.WorkspaceAuthorizationService;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 候选确认：复核摘要、权限、预算、工作区与当前计划规则，并在一个事务内把 READY 候选物化为唯一的运行。
 *
 * <p>确认前复核候选摘要、输入摘要、输入版本、当前分组最新版本、工作区基线与文件版本；创建事务内再锁住候选行复核一次，重复确认返回已有运行而不是再派发。
 */
class PlanCandidateConfirmationService {
  private final PlanCandidateStoreService store;
  private final PlanCandidateWorkspaceService workspaces;
  private final PlanCandidateValidator validator;
  private final ExecutionScopeResolver scopes;
  private final CandidateRunPersister taskCreations;
  private final CandidateRunDispatcher runs;
  private final PlanningRunReader records;
  private final CandidateBudgetValidator budgetValidator;
  private final TransactionTemplate tx;

  PlanCandidateConfirmationService(
      PlanCandidateStoreService store,
      PlanCandidateWorkspaceService workspaces,
      PlanCandidateValidator validator,
      ExecutionScopeResolver scopes,
      CandidateRunPersister taskCreations,
      CandidateRunDispatcher runs,
      PlanningRunReader records,
      CandidateBudgetValidator budgetValidator,
      TransactionTemplate tx) {
    this.store = store;
    this.workspaces = workspaces;
    this.validator = validator;
    this.scopes = scopes;
    this.taskCreations = taskCreations;
    this.runs = runs;
    this.records = records;
    this.budgetValidator = budgetValidator;
    this.tx = tx;
  }

  PlanCandidateService.Confirmed confirm(
      String id, PlanCandidateService.ConfirmRequest request) {
    ExecutionScope scope = scopes.current();
    PlanCandidateEntity row = store.requireById(id, scope.ownerScopeKey());
    requireMatchingSummaries(row, request);
    // 已确认的候选再次确认时返回原运行，不再创建或派发。
    if (row.getRunId() != null) return confirmed(row.getRunId());
    requireConfirmable(row, request);
    PlanCandidateInput input = store.read(row.getInputJson(), PlanCandidateInput.class);
    PlanCandidateDraft draft = store.read(row.getCandidateJson(), PlanCandidateDraft.class);
    workspaces.requireCurrent(row, input);
    var validated = requireCurrentPlan(draft, input, row);
    ModelMode requestedMode =
        ModelMode.fromCode(request.modelMode() == null ? "mock" : request.modelMode().strip());
    var actualModes = actualModes(validated.nodes(), requestedMode);
    var actualModels = actualModels(actualModes, requestedMode);
    if (request.maxCostMicros() != null)
      budgetValidator.requireBudgetPricing(actualModels, request.maxCostMicros());
    ModelMode mode = actualModes.size() == 1 ? actualModes.iterator().next() : requestedMode;
    String workspaceId = workspaces.prepare(row, input);
    var prepared = workspaces.prepareScope(workspaceId, input);
    CandidateRunPlan plan =
        CandidateRunPlanFactory.create(
            validated.normalized(), row.getCandidateSha256(), validated.nodes());
    String taskId = UUID.randomUUID().toString();
    String runId = UUID.randomUUID().toString();
    var task = taskDefinition(taskId, input, mode, workspaceId);
    var limits = new RunLimits(null, null, null, null, null, null, null, null);
    var alreadyCreated = new AtomicReference<String>();
    tx.executeWithoutResult(
        status ->
            materialize(
                id,
                scope,
                request,
                input,
                draft,
                task,
                runId,
                limits,
                plan,
                prepared,
                alreadyCreated));
    if (alreadyCreated.get() != null) return confirmed(alreadyCreated.get());
    // 事务提交后才派发；创建失败时不会留下已派发但没有计划或授权的运行。
    runs.dispatchConfirmed(runId);
    return new PlanCandidateService.Confirmed(runId, taskId, workspaceId);
  }

  /** 事务内锁住候选再复核，避免预检查之后候选或工作区被改写；并发确认只创建一个运行。 */
  private void materialize(
      String id,
      ExecutionScope scope,
      PlanCandidateService.ConfirmRequest request,
      PlanCandidateInput input,
      PlanCandidateDraft draft,
      TaskDefinition task,
      String runId,
      RunLimits limits,
      CandidateRunPlan plan,
      WorkspaceAuthorizationService.Prepared prepared,
      AtomicReference<String> alreadyCreated) {
    var locked = store.lockById(id, scope.ownerScopeKey()).orElseThrow(() -> new org.springframework.dao.EmptyResultDataAccessException(1));
    if (locked.getRunId() != null) {
      alreadyCreated.set(locked.getRunId());
      return;
    }
    requireConfirmable(locked, request);
    workspaces.requireCurrent(locked, input);
    requireCurrentPlan(draft, input, locked);
    taskCreations.persistFromCandidate(
        task,
        UUID.fromString(runId),
        limits,
        "候选计划已确认并创建运行。",
        plan,
        prepared,
        request.maxCostMicros());
    if (store.consume(id, runId) != 1)
      throw new IllegalStateException("候选已被其他确认消费，请刷新后查看运行。");
  }

  private PlanCandidateService.Confirmed confirmed(String runId) {
    var run = records.runSnapshot(runId);
    return new PlanCandidateService.Confirmed(
        runId, run.taskId().toString(), taskWorkspace(run.taskId().toString()));
  }

  private String taskWorkspace(String taskId) {
    return records.taskDefinition(taskId).workspaceId();
  }

  /** 确认条件不满足时给出可操作原因，不自动修补候选或授权。 */
  private void requireConfirmable(
      PlanCandidateEntity row, PlanCandidateService.ConfirmRequest request) {
    if (!PlanCandidateStatus.READY.name().equals(row.getStatus()))
      throw new IllegalStateException("候选当前状态为 " + row.getStatus() + "，不能创建运行。");
    if (store.latestInputVersion(row.getGroupId()) != row.getInputVersion())
      throw new IllegalStateException("已有更新的输入版本，请重新生成计划后再确认。");
    if (request.maxCostMicros() != null
        && (request.maxCostMicros() < 1 || request.maxCostMicros() > 1_000_000_000L))
      throw new IllegalArgumentException("费用硬预算超出允许范围。");
  }

  /** 客户端回传的候选摘要、输入摘要和版本必须与保存值一致。 */
  private void requireMatchingSummaries(
      PlanCandidateEntity row, PlanCandidateService.ConfirmRequest request) {
    if (request == null
        || request.expectedCandidateSha256() == null
        || !request.expectedCandidateSha256().matches("[0-9a-f]{64}")
        || !request.expectedCandidateSha256().equals(row.getCandidateSha256()))
      throw new IllegalStateException("候选摘要与确认时不一致，请刷新后重新确认。");
    if (request.expectedInputSha256() == null
        || !request.expectedInputSha256().equals(row.getInputSha256()))
      throw new IllegalStateException("任务输入已变化，请重新生成计划。");
    if (request.expectedInputVersion() != row.getInputVersion())
      throw new IllegalStateException("输入版本已变化，请刷新候选后再确认。");
  }

  /** 已校验候选必须在当前定义版本和规则下仍然有效，摘要必须与保存值一致。 */
  private PlanCandidateValidator.Result requireCurrentPlan(
      PlanCandidateDraft draft, PlanCandidateInput input, PlanCandidateEntity row) {
    var result =
        validator.validate(
            draft,
            input,
            row.getOwnerScopeKey(),
            store.readPlanning(row.getAgentAliasesJson()));
    if (!result.valid() || !row.getCandidateSha256().equals(result.candidateSha256()))
      throw new IllegalStateException("候选在当前 Agent 定义或计划规则下已失效，请重新生成。");
    return result;
  }

  private static LinkedHashSet<ModelMode> actualModes(
      List<PlanCandidateValidator.ResolvedNode> nodes, ModelMode requestedMode) {
    var actualModes = new LinkedHashSet<ModelMode>();
    for (var resolved : nodes) {
      var binding = resolved.binding();
      actualModes.add(
          binding != null && "FIXED".equals(binding.modelStrategy())
              ? ModelMode.fromCode(binding.modelMode())
              : requestedMode);
    }
    return actualModes;
  }

  private static LinkedHashSet<ModelKind> actualModels(
      LinkedHashSet<ModelMode> actualModes, ModelMode requestedMode) {
    var actualModels = new LinkedHashSet<ModelKind>();
    for (ModelMode actualMode : actualModes) actualModels.add(ModelKind.forMode(actualMode));
    actualModels.add(ModelKind.forMode(requestedMode));
    return actualModels;
  }

  private static TaskDefinition taskDefinition(
      String taskId, PlanCandidateInput input, ModelMode mode, String workspaceId) {
    return new TaskDefinition(
        UUID.fromString(taskId),
        input.goal(),
        Scenario.NORMAL,
        mode,
        workspaceId,
        input.fileScopes().getFirst(),
        !input.readOnly(),
        null,
        false,
        input.constraints(),
        input.acceptanceCriteria(),
        TaskEntryKind.CODE,
        input.fileScopes(),
        Map.of(),
        Map.of());
  }
}
