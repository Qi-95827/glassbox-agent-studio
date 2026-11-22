package dev.workbench.orchestration;

import dev.workbench.bug.BugIntakeService;
import dev.workbench.bug.BugService;
import dev.workbench.domain.*;
import dev.workbench.framework.agent.execution.AgentExecutionRequest;
import dev.workbench.framework.agent.execution.AgentExecutor;
import dev.workbench.framework.agent.execution.AgentRuntime;
import dev.workbench.framework.context.AgentContextAssembler;
import dev.workbench.framework.context.AgentContextInput;
import dev.workbench.framework.context.AgentInput;
import dev.workbench.framework.control.RunControl;
import dev.workbench.framework.control.StopExecution;
import dev.workbench.framework.tool.UncertainToolEffectException;
import dev.workbench.store.AgentAssemblyRecordService;
import dev.workbench.store.RunCheckpointStore;
import dev.workbench.store.RunCreationStore;
import dev.workbench.store.RunEvidenceRecordService;
import dev.workbench.store.RunStateStore;
import dev.workbench.task.CostService;
import dev.workbench.workspace.WorkspaceAuthorizationService;
import java.util.List;

/** 未使用动态计划的单 Agent 顺序执行：分析、按需开发、回归和审查交接。 */
final class DirectRunExecutor {
  private final RunCreationStore creation;
  private final RunStateStore runs;
  private final RunEvidenceRecordService evidence;
  private final RunCheckpointStore checkpointStore;
  private final AgentAssemblyRecordService assemblyRecords;
  private final AgentExecutor agent;
  private final AgentContextAssembler contexts;
  private final BugService bugs;
  private final BugIntakeService intakes;
  private final RunCheckpointSupport checkpoints;
  private final RunHandoffFactory handoffs;
  private final RunFailureHandler failures;
  private final RunCommandService commands;
  private final RunReviewCoordinator review;
  private final WorkspaceAuthorizationService workspaceAuthorization;
  private final CostService costs;
  private final dev.workbench.tool.ToolApprovalService approvals;
  private final org.springframework.context.ApplicationEventPublisher publisher;

  DirectRunExecutor(
      RunCreationStore creation,
      RunStateStore runs,
      RunEvidenceRecordService evidence,
      RunCheckpointStore checkpointStore,
      AgentAssemblyRecordService assemblyRecords,
      AgentExecutor agent,
      AgentContextAssembler contexts,
      BugService bugs,
      BugIntakeService intakes,
      RunCheckpointSupport checkpoints,
      RunHandoffFactory handoffs,
      RunFailureHandler failures,
      RunCommandService commands,
      RunReviewCoordinator review,
      WorkspaceAuthorizationService workspaceAuthorization,
      CostService costs,
      dev.workbench.tool.ToolApprovalService approvals,
      org.springframework.context.ApplicationEventPublisher publisher) {
    this.creation = creation;
    this.runs = runs;
    this.evidence = evidence;
    this.checkpointStore = checkpointStore;
    this.assemblyRecords = assemblyRecords;
    this.agent = agent;
    this.contexts = contexts;
    this.bugs = bugs;
    this.intakes = intakes;
    this.checkpoints = checkpoints;
    this.handoffs = handoffs;
    this.failures = failures;
    this.commands = commands;
    this.review = review;
    this.workspaceAuthorization = workspaceAuthorization;
    this.costs = costs;
    this.approvals = approvals;
    this.publisher = publisher;
  }

  void execute(ExecutionState state) {
    try {
      if (!runAnalysisPhase(state)) return;
      if (state.task.writeEnabled()) runCoderPhase(state);
      runRegression(state);
      review.reviewAndFinish(state);
    } catch (StopExecution e) {
      finishUnsuccessful(state, e.stopCause(), e.getMessage());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      finishUnsuccessful(state, StopCause.INTERRUPTED, "进程停止，调用结果可能未知。");
    } catch (WorkspaceRecoveryConflictException e) {
      if (state.recovery && !state.agentStarted && state.run.parentId() != null) {
        creation.rejectRecoveryPrecondition(
            state.runId,
            state.control.rounds(),
            state.control.tools(),
            state.control.elapsed(),
            e.getMessage());
      } else {
        finishUnsuccessful(state, StopCause.UNCLASSIFIED_FAILURE, e.getMessage());
      }
    } catch (UncertainToolEffectException e) {
      finishUnsuccessful(
          state,
          StopCause.TOOL_EFFECT_UNKNOWN,
          "工具写操作结果未知，请先检查工作区或外部状态，不要盲目重试。");
    } catch (Exception e) {
      finishUnsuccessful(
          state, StopCause.UNCLASSIFIED_FAILURE, "执行失败，请结合运行记录检查模型、工具或状态。");
    } finally {
      commands.clearControl(state.runId);
    }
  }

  private boolean runAnalysisPhase(ExecutionState state) throws InterruptedException {
    state.control.check();
    evidence.event(state.runId, EventType.RUN_STARTED, "运行开始。");
    if (state.task.bugId() != null && !reproduce(state)) return false;
    if (state.stage == CheckpointStage.BEFORE_REVIEW && state.recovery) {
      reuseAnalysis(state);
    } else {
      analyze(state);
    }
    state.control.check();
    state.analysisAnswer = state.answer;
    state.upstreamParty = HandoffParty.ANALYSIS_AGENT;
    return true;
  }

  private boolean reproduce(ExecutionState state) {
    evidence.event(state.runId, EventType.TEST_STARTED, "正在容器中观察模拟 Bug 的测试状态。");
    state.initial =
        bugs.runForTask(
            state.runId,
            state.task.bugId(),
            state.task.workspaceId(),
            BugService.CheckKind.REPRODUCE,
            state.control::check);
    intakes.triage(state.initial);
    evidence.event(
        state.runId,
        EventType.TEST_FINISHED,
        "检测记录 "
            + state.initial.id()
            + "："
            + state.initial.status()
            + "。失败不自动证明特定 Bug 已复现。");
    if (state.initial.status() == BugService.CheckStatus.FAILURES_OBSERVED) return true;
    String reason = "未观察到测试失败或验证条件不足，暂停自动修改；检测结果：" + state.initial.status();
    state.control.save(RunStatus.BLOCKED, reason, null, StopCause.VALIDATION_MISSING);
    evidence.event(state.runId, EventType.BLOCKED, reason);
    return false;
  }

  private void reuseAnalysis(ExecutionState state) {
    if (state.run.parentId() == null
        || state.task.bugId() != null
        || state.task.writeEnabled())
      throw new IllegalStateException("当前任务不支持从分析后检查点恢复。");
    WorkspaceReviewCheckpoint workspaceCheckpoint = null;
    String analysisReference = state.previousResult;
    if (state.task.workspaceId() != null) {
      workspaceCheckpoint = evidence.workspaceReviewPayload(state.previousResult);
      checkpoints.requireUnchangedWorkspace(state.task, workspaceCheckpoint);
      analysisReference = workspaceCheckpoint.analysisReference();
    }
    var source =
        evidence.checkpointAnalysisSource(
            state.run.parentId().toString(), analysisReference);
    state.evidenceRun = source.runId().toString();
    state.agentId = source.agentId().toString();
    state.answer = source.result();
    evidence.reuseAnalysis(state.runId, source.agentId());
    evidence.event(
        state.runId, EventType.HANDOFF, "已校验并复用历史分析结果；本次运行未重新调用分析 Agent。");
    String reference =
        evidence.ancestorAnalysisReference(
            state.runId, state.evidenceRun, source.agentId());
    if (workspaceCheckpoint == null) {
      checkpointStore.checkpoint(
          state.runId,
          CheckpointStage.BEFORE_REVIEW,
          state.control.rounds(),
          state.control.tools(),
          state.control.elapsed(),
          reference);
    } else {
      checkpointStore.workspaceReviewCheckpoint(
          state.runId,
          state.control.rounds(),
          state.control.tools(),
          state.control.elapsed(),
          new WorkspaceReviewCheckpoint(
              workspaceCheckpoint.workspaceId(),
              reference,
              workspaceCheckpoint.workspaceFingerprint()));
    }
  }

  private void analyze(ExecutionState state) throws InterruptedException {
    String beforeAnalysis = checkpoints.fingerprintIfEligible(state.task);
    Handoff delegation = handoffs.delegation(state.run, state.task, state.agentId);
    assemblyRecords.startAgentWithDelegation(state.agentId, state.runId, state.task.goal(), delegation);
    state.agentStarted = true;
    evidence.event(
        state.runId,
        EventType.DELEGATED,
        state.recovery
            ? "协调者从检查点重新委派 AgentScope 分析 Agent。"
            : "协调者将分析子任务委派给 AgentScope 分析 Agent。");
    evidence.event(
        state.runId,
        EventType.AGENT_STARTED,
        "AgentScope 分析 Agent 开始执行，实例：" + state.agentId);
    if (state.stage == CheckpointStage.BEFORE_TOOL && !state.recovery) {
      checkpointStore.checkpoint(
          state.runId,
          state.stage,
          state.control.rounds(),
          state.control.tools(),
          state.control.elapsed(),
          null);
    }
    var analystDefinition = assemblyRecords.plannedDefinitionForAgent(state.runId, state.agentId);
    String answer =
        agent.execute(
            new AgentExecutionRequest(
                AgentRole.ANALYST,
                state.agentId,
                contexts.assembleInput(
                    new AgentContextInput(state.task, state.runId, initialCheck(state.initial))),
                state.task.workspaceId(),
                state.task.fileScopes(),
                false,
                state.task.scenario(),
                state.task.modelMode(),
                state.stage,
                state.previousResult,
                runtimeFor(state.runId, state.agentId, state.control),
                analystDefinition.reference(),
                analystDefinition.sha256())
            .withModelConnection(state.task.modelConnectionId())
            .withLocale(state.task.locale()));
    if (answer.length() > 2000) answer = answer.substring(0, 1990) + "…（结果已截断）";
    state.answer = answer;
    state.evidenceRun = state.runId;
    Handoff analysisResult =
        handoffs.analysisResult(
            state.run, state.task, state.agentId, answer, state.evidenceRun, state.initial);
    assemblyRecords.finishAgentWithResult(state.agentId, answer, analysisResult);
    state.agentStarted = false;
    evidence.event(
        state.runId, EventType.HANDOFF, "分析 Agent 已提交结构化结果。准备交给审查 Agent。");
    checkpoints.recordAnalysisCheckpoint(
        state.runId, state.task, state.control, state.agentId, beforeAnalysis);
  }

  private void runCoderPhase(ExecutionState state) throws InterruptedException {
    String coderId = dev.workbench.domain.WorkbenchIds.newId();
    Handoff codingInput =
        handoffs.codingInput(
            state.run,
            state.task,
            state.agentId,
            state.analysisAnswer,
            state.evidenceRun,
            coderId,
            state.initial);
    AgentInput codingPrompt = handoffs.input(codingInput);
    evidence.handoff(HandoffDirection.AGENT_TRANSFER, codingInput);
    evidence.event(state.runId, EventType.DELEGATED, "分析交接已准备，计划接收实例：" + coderId);
    assemblyRecords.startAgentFromHandoff(
        coderId, state.runId, state.task.goal(), AgentRole.CODER, codingInput.handoffId());
    state.agentId = coderId;
    state.activeRole = AgentRole.CODER;
    state.agentStarted = true;
    evidence.event(
        state.runId, EventType.AGENT_STARTED, "AgentScope 开发 Agent 开始执行，实例：" + coderId);
    var coderDefinition = assemblyRecords.plannedDefinitionForAgent(state.runId, coderId);
    String answer =
        agent.execute(
            new AgentExecutionRequest(
                AgentRole.CODER,
                coderId,
                codingPrompt,
                state.task.workspaceId(),
                state.task.fileScopes(),
                true,
                state.task.scenario(),
                state.task.modelMode(),
                CheckpointStage.BEFORE_TOOL,
                null,
                runtimeFor(state.runId, coderId, state.control),
                coderDefinition.reference(),
                coderDefinition.sha256())
            .withModelConnection(state.task.modelConnectionId())
            .withLocale(state.task.locale()));
    if (answer.length() > 1900) answer = answer.substring(0, 1890) + "…（结果已截断）";
    state.answer = answer;
    Handoff codingResult =
        handoffs.attachTestChecks(
            new Handoff(
                state.task.id().toString(),
                state.runId,
                coderId,
                HandoffParty.CODING_AGENT,
                HandoffParty.COORDINATOR,
                state.task.goal(),
                handoffs.constraints(
                    state.task, List.of("开发结果需由代码差异和测试证据核对")),
                HandoffStatus.COMPLETED,
                answer,
                state.runId,
                evidence.toolEvidence(state.runId),
                switch (state.task.modelMode()) {
                  case DEEPSEEK -> HandoffSource.DEEPSEEK_FLASH;
                  case USER -> HandoffSource.MODEL_CONNECTION;
                  case MOCK -> HandoffSource.MOCK_MODEL;
                },
                null),
            state.initial);
    assemblyRecords.finishAgentWithResult(coderId, answer, codingResult);
    state.agentStarted = false;
    evidence.event(
        state.runId, EventType.HANDOFF, "开发 Agent 已返回结果；是否修改和测试通过以实际记录为准。");
    state.upstreamParty = HandoffParty.CODING_AGENT;
    state.control.check();
  }

  private void runRegression(ExecutionState state) {
    if (state.task.bugId() == null || !state.task.writeEnabled()) return;
    evidence.event(state.runId, EventType.TEST_STARTED, "正在容器中执行回归检查。");
    state.regression =
        bugs.runForTask(
            state.runId,
            state.task.bugId(),
            state.task.workspaceId(),
            BugService.CheckKind.REGRESSION,
            state.control::check);
    intakes.regression(state.regression);
    evidence.event(
        state.runId,
        EventType.TEST_FINISHED,
        "回归检测记录 "
            + state.regression.id()
            + "："
            + state.regression.status()
            + "；需结合具体测试与差异审查。");
  }

  private void finishUnsuccessful(ExecutionState state, StopCause cause, String reason) {
    failures.finishUnsuccessful(
        state.runId,
        state.agentId,
        state.agentStarted,
        state.activeRole,
        state.task,
        state.control,
        cause,
        reason);
  }

  private AgentRuntime runtimeFor(String runId, String agentId, RunControl control) {
    return new ControlledAgentRuntime(
        runId,
        agentId,
        control,
        assemblyRecords,
        runs,
        evidence,
        null,
        costs,
        workspaceAuthorization,
        null,
        null,
        approvals,
        publisher);
  }

  private static dev.workbench.framework.context.InitialCheck initialCheck(BugService.Check check) {
    if (check == null) return null;
    return new dev.workbench.framework.context.InitialCheck(
        check.id(),
        check.runId(),
        check.bugId(),
        check.workspaceId(),
        check.kind().name(),
        check.status().name(),
        check.workspaceFingerprint(),
        check.output());
  }

  /** 单次直接执行期间可变的协作状态，供各阶段与统一异常结算共享。 */
  static final class ExecutionState {
    final String runId;
    final RunSnapshot run;
    final TaskDefinition task;
    final RunControl control;
    final CheckpointStage stage;
    final String previousResult;
    final boolean recovery;
    String agentId;
    boolean agentStarted;
    AgentRole activeRole = AgentRole.ANALYST;
    BugService.Check initial;
    String answer;
    String analysisAnswer;
    String evidenceRun;
    HandoffParty upstreamParty = HandoffParty.ANALYSIS_AGENT;
    BugService.Check regression;

    ExecutionState(
        String runId,
        RunSnapshot run,
        TaskDefinition task,
        RunControl control,
        CheckpointStage stage,
        String previousResult,
        boolean recovery) {
      this.runId = runId;
      this.run = run;
      this.task = task;
      this.control = control;
      this.stage = stage;
      this.previousResult = previousResult;
      this.recovery = recovery;
    }
  }
}
