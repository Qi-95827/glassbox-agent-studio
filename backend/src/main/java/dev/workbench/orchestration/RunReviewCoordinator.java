package dev.workbench.orchestration;

import dev.workbench.bug.BugService;
import dev.workbench.domain.*;
import dev.workbench.framework.agent.execution.AgentExecutionRequest;
import dev.workbench.framework.agent.execution.AgentExecutor;
import dev.workbench.framework.context.AgentInput;
import dev.workbench.store.AgentAssemblyRecordService;
import dev.workbench.store.RunEvidenceRecordService;
import dev.workbench.store.RunStateStore;
import dev.workbench.store.ReviewCompletion;
import dev.workbench.task.CostService;
import dev.workbench.workspace.WorkspaceAuthorizationService;
import java.util.List;

/** 审查交接、审查 Agent 调用和最终运行结算。 */
final class RunReviewCoordinator {
  private final RunStateStore runs;
  private final AgentAssemblyRecordService assemblyRecords;
  private final RunEvidenceRecordService evidence;
  private final AgentExecutor agent;
  private final RunCommandService commands;
  private final RunHandoffFactory handoffs;
  private final WorkspaceAuthorizationService workspaceAuthorization;
  private final CostService costs;
  private final Object stateLock;

  RunReviewCoordinator(
      RunStateStore runs,
      AgentAssemblyRecordService assemblyRecords,
      RunEvidenceRecordService evidence,
      AgentExecutor agent,
      RunCommandService commands,
      RunHandoffFactory handoffs,
      WorkspaceAuthorizationService workspaceAuthorization,
      CostService costs,
      Object stateLock) {
    this.runs = runs;
    this.assemblyRecords = assemblyRecords;
    this.evidence = evidence;
    this.agent = agent;
    this.commands = commands;
    this.handoffs = handoffs;
    this.workspaceAuthorization = workspaceAuthorization;
    this.costs = costs;
    this.stateLock = stateLock;
  }

  void reviewAndFinish(DirectRunExecutor.ExecutionState state) throws InterruptedException {
    ReviewOutcome outcome = executeReviewer(state);
    finishReview(state, outcome);
  }

  private ReviewOutcome executeReviewer(DirectRunExecutor.ExecutionState state)
      throws InterruptedException {
    String reviewerId = dev.workbench.domain.WorkbenchIds.newId();
    String verification =
        state.regression == null
            ? "没有执行本次回归测试"
            : "本次回归检测记录 " + state.regression.id() + "，结果 " + state.regression.status();
    Handoff transfer =
        handoffs.reviewTransfer(
            state.run,
            state.task,
            state.agentId,
            state.upstreamParty,
            state.answer,
            state.analysisAnswer,
            state.evidenceRun,
            reviewerId,
            verification,
            state.initial,
            state.regression);
    AgentInput reviewInput = handoffs.input(transfer);
    evidence.handoff(HandoffDirection.AGENT_TRANSFER, transfer);
    evidence.event(state.runId, EventType.DELEGATED, "审查交接已准备，计划接收实例：" + reviewerId);
    assemblyRecords.startAgentFromHandoff(
        reviewerId, state.runId, state.task.goal(), AgentRole.REVIEWER, transfer.handoffId());
    state.agentId = reviewerId;
    state.activeRole = AgentRole.REVIEWER;
    state.agentStarted = true;
    evidence.event(
        state.runId, EventType.AGENT_STARTED, "AgentScope 审查 Agent 开始执行，实例：" + reviewerId);
    var reviewDefinition = assemblyRecords.plannedDefinitionForAgent(state.runId, reviewerId);
    String review =
        agent.execute(
            new AgentExecutionRequest(
                AgentRole.REVIEWER,
                reviewerId,
                reviewInput,
                null,
                List.of(),
                false,
                state.task.scenario(),
                state.task.modelMode(),
                CheckpointStage.BEFORE_TOOL,
                null,
                runtimeFor(state.runId, reviewerId, state),
                reviewDefinition.reference(),
                reviewDefinition.sha256())
            .withModelConnection(state.task.modelConnectionId()));
    if (review.length() > 1900) review = review.substring(0, 1890) + "…（结果已截断）";
    Handoff reviewResult =
        handoffs.attachTestChecks(
            new Handoff(
                state.task.id().toString(),
                state.runId,
                reviewerId,
                HandoffParty.REVIEW_AGENT,
                HandoffParty.COORDINATOR,
                state.task.goal(),
                List.of("审查基于模型输出和模拟证据，不能代替真实测试"),
                HandoffStatus.COMPLETED,
                review,
                state.evidenceRun,
                evidence.toolEvidence(state.evidenceRun),
                switch (state.task.modelMode()) {
                  case DEEPSEEK -> HandoffSource.DEEPSEEK_FLASH;
                  case USER -> HandoffSource.MODEL_CONNECTION;
                  case MOCK -> HandoffSource.MOCK_MODEL;
                },
                null),
            state.initial,
            state.regression);
    return new ReviewOutcome(reviewerId, review, reviewResult);
  }

  private void finishReview(DirectRunExecutor.ExecutionState state, ReviewOutcome outcome) {
    boolean verificationBlocked = state.task.bugId() != null || state.task.writeEnabled();
    while (true) {
      state.control.check();
      synchronized (stateLock) {
        if (commands.paused().contains(state.runId) || commands.cancelled().contains(state.runId))
          continue;
        String finalMessage =
            "分析结果：\n"
                + state.analysisAnswer.substring(0, Math.min(650, state.analysisAnswer.length()))
                + (state.task.writeEnabled()
                    ? "\n\n开发结果：\n"
                        + state.answer.substring(0, Math.min(550, state.answer.length()))
                    : "")
                + "\n\n审查结果：\n"
                + outcome.review().substring(0, Math.min(700, outcome.review().length()));
        if (verificationBlocked) {
          String reason;
          StopCause cause;
          if (state.task.bugId() == null) {
            reason = "开发流程已结束，仍需用户检查差异并验证验收条件。";
            cause = StopCause.USER_REVIEW;
          } else if (state.regression == null) {
            reason = "仅完成分析，未执行本次回归；尚不能报告 Bug 已修复。";
            cause = StopCause.VALIDATION_MISSING;
          } else if (state.regression.status() == BugService.CheckStatus.PASSED) {
            reason = "回归测试通过，仍需用户结合差异与原问题审查。";
            cause = StopCause.USER_REVIEW;
          } else {
            reason = "回归检查未通过或无法执行；不得报告 Bug 已修复。";
            cause = StopCause.CHECK_FAILED;
          }
          runs.finishReviewedRun(
              new ReviewCompletion(
                  java.util.UUID.fromString(state.runId),
                  java.util.UUID.fromString(outcome.reviewerId()),
                  outcome.review(),
                  outcome.reviewResult(),
                  RunStatus.BLOCKED,
                  cause,
                  reason,
                  finalMessage,
                  state.control.rounds(),
                  state.control.tools(),
                  state.control.elapsed()));
        } else {
          runs.finishReviewedRun(
              new ReviewCompletion(
                  java.util.UUID.fromString(state.runId),
                  java.util.UUID.fromString(outcome.reviewerId()),
                  outcome.review(),
                  outcome.reviewResult(),
                  RunStatus.COMPLETED,
                  null,
                  null,
                  finalMessage,
                  state.control.rounds(),
                  state.control.tools(),
                  state.control.elapsed()));
        }
        state.agentStarted = false;
        break;
      }
    }
  }

  private ControlledAgentRuntime runtimeFor(
      String runId, String reviewerId, DirectRunExecutor.ExecutionState state) {
    return new ControlledAgentRuntime(
        runId,
        reviewerId,
        state.control,
        assemblyRecords,
        runs,
        evidence,
        null,
        costs,
        workspaceAuthorization);
  }

  private record ReviewOutcome(String reviewerId, String review, Handoff reviewResult) {}
}
