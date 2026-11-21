package dev.workbench.store;

import dev.workbench.domain.*;
import dev.workbench.framework.workflow.plan.WorkflowTemplate;
import dev.workbench.persistence.repository.InvocationRepository;
import dev.workbench.persistence.repository.RunData;
import dev.workbench.persistence.repository.RunRepository;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** 运行读取、创建/恢复入口、状态更新、终态结算和中断收口。 */
@Service
public class RunStateStore {
  private final TransactionTemplate tx;
  private final RunRepository runRows;
  private final InvocationRepository invocationRows;
  private final dev.workbench.framework.workflow.plan.PlanOperations plans;
  private final RunEvidenceRecordService evidence;
  private final AgentAssemblyRecordService agents;
  private final RunCreationStore creation;
  private final RunCheckpointStore checkpoints;
  private final RunCompletionWaiter completionWaiter;

  public RunStateStore(
      TransactionTemplate tx,
      RunRepository runRows,
      InvocationRepository invocationRows,
      dev.workbench.framework.workflow.plan.PlanOperations plans,
      RunEvidenceRecordService evidence,
      AgentAssemblyRecordService agents,
      RunCreationStore creation,
      RunCheckpointStore checkpoints,
      RunCompletionWaiter completionWaiter) {
    this.tx = tx;
    this.runRows = runRows;
    this.invocationRows = invocationRows;
    this.plans = plans;
    this.evidence = evidence;
    this.agents = agents;
    this.creation = creation;
    this.checkpoints = checkpoints;
    this.completionWaiter = completionWaiter;
  }

  public Map<String, Object> run(String id) {
    var fields = new java.util.LinkedHashMap<String, Object>(findRun(id).apiFields());
    var status = RunStatus.valueOf(String.valueOf(fields.get("STATUS")));
    var category = BlockCategory.from(status, (String) fields.get("STOP_CAUSE"));
    if (category != null) fields.put("BLOCK_CATEGORY", category.name());
    return fields;
  }

  public RunSnapshot runSnapshot(String id) {
    return findRun(id).snapshot();
  }

  private RunData findRun(String id) {
    return runRows.findRunById(id).orElseThrow(() -> new EmptyResultDataAccessException(1));
  }

  public List<Map<String, Object>> runs(String taskId) {
    return runRows.findByTaskId(taskId).stream().map(RunData::apiFields).toList();
  }

  public boolean hasRecovery(String parent) {
    return runRows.hasRecovery(parent);
  }

  public void createRun(
      String id,
      String taskId,
      String parent,
      int rounds,
      int tools,
      Integer maxRounds,
      Integer maxTools,
      Integer maxSeconds,
      long elapsed) {
    creation.createRun(id, taskId, parent, rounds, tools, maxRounds, maxTools, maxSeconds, elapsed);
  }

  public void createRun(
      String id,
      String taskId,
      String parent,
      int rounds,
      int tools,
      Integer maxRounds,
      Integer maxTools,
      Integer maxPerTool,
      Integer maxSeconds,
      long elapsed) {
    creation.createRun(id, taskId, parent, rounds, tools, maxRounds, maxTools, maxPerTool, maxSeconds, elapsed);
  }

  public void createRun(
      String id,
      String taskId,
      String parent,
      int rounds,
      int tools,
      Integer maxRounds,
      Integer maxTools,
      Integer maxPerTool,
      Long maxReportedTokens,
      Integer maxSeconds,
      long elapsed) {
    creation.createRun(id, taskId, parent, rounds, tools, maxRounds, maxTools, maxPerTool, maxReportedTokens, maxSeconds, elapsed);
  }

  public void createRun(
      String id,
      String taskId,
      String parent,
      int rounds,
      int tools,
      Integer maxRounds,
      Integer maxTools,
      Integer maxPerTool,
      Long maxReportedTokens,
      Integer maxSeconds,
      long elapsed,
      boolean includeCoder) {
    creation.createRun(id, taskId, parent, rounds, tools, maxRounds, maxTools, maxPerTool, maxReportedTokens, maxSeconds, elapsed, includeCoder);
  }

  public void createRun(
      String id,
      String taskId,
      String parent,
      int rounds,
      int tools,
      Integer maxRounds,
      Integer maxTools,
      Integer maxPerTool,
      Long maxReportedTokens,
      Integer maxIdenticalTools,
      Integer maxSeconds,
      long elapsed,
      boolean includeCoder) {
    creation.createRun(id, taskId, parent, rounds, tools, maxRounds, maxTools, maxPerTool, maxReportedTokens, maxIdenticalTools, maxSeconds, elapsed, includeCoder);
  }

  public void createRun(
      String id,
      String taskId,
      String parent,
      int rounds,
      int tools,
      Integer maxRounds,
      Integer maxTools,
      Integer maxPerTool,
      Long maxReportedTokens,
      Integer maxIdenticalTools,
      Integer maxSeconds,
      long elapsed,
      boolean includeCoder,
      Integer maxAgentRounds,
      Integer maxAgentTools) {
    creation.createRun(id, taskId, parent, rounds, tools, maxRounds, maxTools, maxPerTool, maxReportedTokens, maxIdenticalTools, maxSeconds, elapsed, includeCoder, maxAgentRounds, maxAgentTools);
  }

  public void createRecoveryRun(
      UUID runId, RunSnapshot original, CheckpointSnapshot checkpoint) {
    creation.createRecoveryRun(runId, original, checkpoint);
  }

  public void createWorkflowRecoveryRun(
      UUID id,
      RunSnapshot original,
      WorkflowTemplate template,
      boolean reviewRequired,
      int rounds,
      int tools,
      long elapsed) {
    creation.createWorkflowRecoveryRun(
        id, original, template, reviewRequired, rounds, tools, elapsed);
  }

  public void rejectRecoveryBeforeDispatch(String runId) {
    creation.rejectRecoveryBeforeDispatch(runId);
  }

  public void rejectRecoveryPrecondition(
      String runId, int rounds, int tools, long elapsed, String reason) {
    creation.rejectRecoveryPrecondition(runId, rounds, tools, elapsed, reason);
  }

  public void update(
      String id,
      RunStatus status,
      String reason,
      String result,
      int rounds,
      int tools,
      long elapsed) {
    update(id, status, reason, result, rounds, tools, elapsed, null);
  }

  /** 状态和停止原因在同一事务保存；旧记录或不适用时原因保持未知。 */
  public void update(
      String id,
      RunStatus status,
      String reason,
      String result,
      int rounds,
      int tools,
      long elapsed,
      StopCause stopCause) {
    if (stopCause != null && stopCause.status() != status)
      throw new IllegalArgumentException("停止原因与运行状态不一致。");
    tx.executeWithoutResult(
        s -> {
          if (!runRows.updateActive(
              id, status, reason, result, rounds, tools, elapsed, stopCause, RecordSupport.now()))
            throw new IllegalStateException("运行不存在或已进入终态，不能再更新。");
          if (!status.canCancel()) plans.settle(id, RecordSupport.now());
        });
    if (!status.canCancel()) signalAfterCommit(id);
  }

  /** 只有实际提交后才通知；回滚的外层事务不得唤醒等待方。 */
  private void signalAfterCommit(String runId) {
    if (org.springframework.transaction.support.TransactionSynchronizationManager
        .isSynchronizationActive()) {
      org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
          new org.springframework.transaction.support.TransactionSynchronization() {
            @Override
            public void afterCommit() {
              completionWaiter.signal(runId);
            }
          });
    } else {
      completionWaiter.signal(runId);
    }
  }

  /** 审查完成、交接和整个运行终态一起提交，外部模型调用不在事务内。 */
  public void finishReviewedRun(ReviewCompletion completion) {
    Objects.requireNonNull(completion, "运行终态不能为。");
    tx.executeWithoutResult(
        ignored -> {
          String runId = completion.runId().toString();
          String agentId = completion.agentId().toString();
          if (!invocationRows.isRunningReviewer(runId, agentId))
            throw new IllegalStateException("当前运行没有可完成的审查 Agent?");
          agents.finishAgentWithResult(agentId, completion.review(), completion.resultHandoff());
          evidence.event(runId, EventType.HANDOFF, "审查 Agent 已将审查结果返回协调者。");
          if (!runRows.finishReview(
              runId,
              completion.status(),
              completion.reason(),
              completion.result(),
              completion.rounds(),
              completion.tools(),
              completion.elapsedMs(),
              completion.stopCause(),
              RecordSupport.now()))
            throw new IllegalStateException("运行已暂停或不在执行中，不能提交审查终态。");
          plans.settle(runId, RecordSupport.now());
          if (completion.status() == RunStatus.BLOCKED)
            evidence.event(runId, EventType.BLOCKED, completion.reason());
          else
            evidence.event(
                runId, EventType.RUN_COMPLETED, "Agent 协作已结束；代码修改和测试需以实际证据为准。");
        });
    if (!completion.status().canCancel()) signalAfterCommit(completion.runId().toString());
  }

  /** 受控停止与 Agent、运行终态、事件和系统交接一起提交。 */
  public void finishStoppedRun(StopCompletion completion) {
    Objects.requireNonNull(completion, "停止记录不能为空");
    tx.executeWithoutResult(
        ignored -> {
          String runId = completion.runId().toString();
          if (completion.agentId() != null)
            agents.finishAgent(
                completion.agentId().toString(),
                InvocationStatus.fromRunStatus(completion.cause().status()),
                null);
          settleStoppedRun(
              runId,
              completion.cause(),
              completion.reason(),
              completion.rounds(),
              completion.tools(),
              completion.elapsedMs());
          if (completion.systemHandoff() != null)
            evidence.handoff(HandoffDirection.RESULT, completion.systemHandoff());
        });
  }

  public void settleStoppedRun(
      String runId, StopCause cause, String reason, int rounds, int tools, long elapsed) {
    RunStatus status = cause.status();
    update(runId, status, reason, null, rounds, tools, elapsed, cause);
    EventType eventType =
        switch (status) {
          case INTERRUPTED -> EventType.INTERRUPTED;
          case FAILED -> EventType.FAILED;
          case BLOCKED -> EventType.BLOCKED;
          default -> EventType.fromStopStatus(status);
        };
    evidence.event(runId, eventType, reason);
  }

  public void checkpoint(
      String runId, CheckpointStage stage, int rounds, int tools, long elapsed, String result) {
    checkpoints.checkpoint(runId, stage, rounds, tools, elapsed, result);
  }

  public void workspaceReviewCheckpoint(
      String runId, int rounds, int tools, long elapsed, WorkspaceReviewCheckpoint payload) {
    checkpoints.workspaceReviewCheckpoint(runId, rounds, tools, elapsed, payload);
  }

  public Map<String, Object> overview() {
    return runRows.countByStatus().apiFields();
  }

  public Map<String, Object> overview(List<String> taskIds) {
    if (taskIds == null || taskIds.isEmpty())
      return new dev.workbench.persistence.repository.RunCounts(0L, 0L, 0L).apiFields();
    return runRows
        .countByStatusForTasks(taskIds, RunStatus.RUNNING.name(), RunStatus.COMPLETED.name())
        .apiFields();
  }

  public void interruptRunning() {
    tx.executeWithoutResult(
        s -> {
          for (var row : runRows.findForStartupInterruption()) {
            String id = row.id();
            runRows.interrupt(id, "进程中断；未结束的调用结果未知。", RecordSupport.now());
            plans.settle(id, RecordSupport.now());
            evidence.event(
                id,
                EventType.INTERRUPTED,
                row.planId() == null ? "运行中断，可查看支持的检查点。" : "动态工作流中断，已保留执行记录。可查询检查点的可用状态。");
          }
          invocationRows.interruptRunning(RecordSupport.now());
        });
  }
}
