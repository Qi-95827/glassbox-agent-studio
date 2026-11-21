package dev.workbench.store;

import dev.workbench.domain.*;
import dev.workbench.framework.workflow.plan.CandidateRunPlan;
import dev.workbench.framework.workflow.plan.PlanOperations;
import dev.workbench.framework.workflow.plan.WorkflowTemplate;
import dev.workbench.persistence.entity.RunEntity;
import dev.workbench.persistence.repository.RunRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** 运行创建、恢复运行创建和派发前失败收口。 */
@Service
public class RunCreationStore {
  private final TransactionTemplate tx;
  private final PlanOperations plans;
  private final RunRepository runRows;
  private final RunEvidenceRecordService evidence;
  private final TaskRecordService tasks;

  public RunCreationStore(
      TransactionTemplate tx,
      PlanOperations plans,
      RunRepository runRows,
      RunEvidenceRecordService evidence,
      TaskRecordService tasks) {
    this.tx = tx;
    this.plans = plans;
    this.runRows = runRows;
    this.evidence = evidence;
    this.tasks = tasks;
  }

  /** 首运行及计划与任务、创建事件同事务，避免持久化出孤立的半成品。 */
  public void createTaskAndFirstRun(
      TaskDefinition task, UUID runId, RunLimits limits, String message) {
    createTaskAndFirstRun(task, runId, limits, message, null, false);
  }

  public void createTaskAndFirstRun(
      TaskDefinition task,
      UUID runId,
      RunLimits limits,
      String message,
      WorkflowTemplate workflow,
      boolean reviewRequired) {
    tx.executeWithoutResult(
        status -> {
          tasks.createTaskRow(task);
          createRunForWorkflow(
              runId.toString(),
              task.id().toString(),
              null,
              0,
              0,
              limits.maxRounds(),
              limits.maxTools(),
              limits.maxPerTool(),
              limits.maxReportedTokens(),
              limits.maxIdenticalTools(),
              limits.maxSeconds(),
              0,
              task.writeEnabled(),
              limits.maxAgentRounds(),
              limits.maxAgentTools(),
              workflow,
              reviewRequired);
          evidence.event(runId.toString(), EventType.TASK_CREATED, message);
        });
  }

  /** 已确认候选计划的任务、运行、计划、节点规格和创建事件一起提交。 */
  public void createTaskAndFirstRunFromPlan(
      TaskDefinition task,
      UUID runId,
      RunLimits limits,
      String message,
      CandidateRunPlan plan,
      String planTime) {
    tx.executeWithoutResult(
        status -> {
          tasks.createTaskRow(task);
          createRunForCandidatePlan(
              runId.toString(),
              task.id().toString(),
              null,
              0,
              0,
              limits.maxRounds(),
              limits.maxTools(),
              limits.maxPerTool(),
              limits.maxReportedTokens(),
              limits.maxIdenticalTools(),
              limits.maxSeconds(),
              0,
              task.writeEnabled(),
              limits.maxAgentRounds(),
              limits.maxAgentTools(),
              plan);
          evidence.event(runId.toString(), EventType.TASK_CREATED, message);
          evidence.event(runId.toString(), EventType.PLAN_APPROVED, "用户确认候选计划并创建运行。");
        });
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
    createRun(
        id, taskId, parent, rounds, tools, maxRounds, maxTools, maxTools, maxSeconds, elapsed);
  }

  /** 创建运行及固定计划；单工具上限随恢复运行传递。 */
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
    createRun(
        id,
        taskId,
        parent,
        rounds,
        tools,
        maxRounds,
        maxTools,
        maxPerTool,
        null,
        maxSeconds,
        elapsed);
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
    createRun(
        id,
        taskId,
        parent,
        rounds,
        tools,
        maxRounds,
        maxTools,
        maxPerTool,
        maxReportedTokens,
        maxSeconds,
        elapsed,
        false);
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
    createRun(
        id,
        taskId,
        parent,
        rounds,
        tools,
        maxRounds,
        maxTools,
        maxPerTool,
        maxReportedTokens,
        null,
        maxSeconds,
        elapsed,
        includeCoder);
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
    createRun(
        id,
        taskId,
        parent,
        rounds,
        tools,
        maxRounds,
        maxTools,
        maxPerTool,
        maxReportedTokens,
        maxIdenticalTools,
        maxSeconds,
        elapsed,
        includeCoder,
        null,
        null);
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
    createRun(
        id,
        taskId,
        parent,
        rounds,
        tools,
        maxRounds,
        maxTools,
        maxPerTool,
        maxReportedTokens,
        maxIdenticalTools,
        maxSeconds,
        elapsed,
        includeCoder,
        maxAgentRounds,
        maxAgentTools,
        null,
        null,
        false);
  }

  void createRunForWorkflow(
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
      Integer maxAgentTools,
      WorkflowTemplate workflow,
      boolean reviewRequired) {
    createRun(
        id,
        taskId,
        parent,
        rounds,
        tools,
        maxRounds,
        maxTools,
        maxPerTool,
        maxReportedTokens,
        maxIdenticalTools,
        maxSeconds,
        elapsed,
        includeCoder,
        maxAgentRounds,
        maxAgentTools,
        workflow,
        null,
        reviewRequired);
  }

  void createRunForCandidatePlan(
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
      Integer maxAgentTools,
      CandidateRunPlan plan) {
    createRun(
        id,
        taskId,
        parent,
        rounds,
        tools,
        maxRounds,
        maxTools,
        maxPerTool,
        maxReportedTokens,
        maxIdenticalTools,
        maxSeconds,
        elapsed,
        includeCoder,
        maxAgentRounds,
        maxAgentTools,
        null,
        plan,
        false);
  }

  /** 恢复子运行、计划和创建事件一起保存，预算采用父运行与快照的较大值。 */
  public void createRecoveryRun(UUID runId, RunSnapshot original, CheckpointSnapshot checkpoint) {
    if (!checkpoint.runId().equals(original.id()) || original.status() != RunStatus.INTERRUPTED)
      throw new IllegalArgumentException("检查点与中断运行不匹配。");
    tx.executeWithoutResult(
        status -> {
          createRun(
              runId.toString(),
              original.taskId().toString(),
              original.id().toString(),
              Math.max(original.rounds(), checkpoint.rounds()),
              Math.max(original.tools(), checkpoint.tools()),
              original.maxRounds(),
              original.maxTools(),
              original.maxPerTool(),
              original.maxReportedTokens(),
              original.maxIdenticalTools(),
              original.maxSeconds(),
              Math.max(original.elapsedMs(), checkpoint.elapsedMs()),
              false,
              original.maxAgentRounds(),
              original.maxAgentTools(),
              null,
              null,
              false);
          evidence.event(
              runId.toString(),
              EventType.RECOVERY_STARTED,
              "从运。" + original.id() + " 的检查点恢复；原历史保留。");
        });
  }

  /** 调用方在源运行锁和恢复校验事务内创建副本。 */
  public void createWorkflowRecoveryRun(
      UUID id,
      RunSnapshot original,
      WorkflowTemplate template,
      boolean reviewRequired,
      int rounds,
      int tools,
      long elapsed) {
    createRun(
        id.toString(),
        original.taskId().toString(),
        original.id().toString(),
        Math.max(original.rounds(), rounds),
        Math.max(original.tools(), tools),
        original.maxRounds(),
        original.maxTools(),
        original.maxPerTool(),
        original.maxReportedTokens(),
        original.maxIdenticalTools(),
        original.maxSeconds(),
        Math.max(original.elapsedMs(), elapsed),
        false,
        original.maxAgentRounds(),
        original.maxAgentTools(),
        template,
        null,
        reviewRequired);
  }

  /** 仅在线程池明确拒绝时调用；记录成功后父运行才允许再次申请恢复。 */
  public void rejectRecoveryBeforeDispatch(String runId) {
    tx.executeWithoutResult(
        status -> {
          String reason = "恢复请求未能进入执行队列，尚未启动 Agent，可重新申请恢复。";
          if (!runRows.rejectBeforeDispatch(runId, reason, RecordSupport.now()))
            throw new IllegalStateException("恢复派发状态无法安全确认。");
          plans.settle(runId, RecordSupport.now());
          evidence.event(runId, EventType.FAILED, reason);
        });
  }

  /** 仅当前置检查在任何 Agent、模型和工具调用之前失败时，才允许父运行重试。 */
  public void rejectRecoveryPrecondition(
      String runId, int rounds, int tools, long elapsed, String reason) {
    tx.executeWithoutResult(
        status -> {
          if (!runRows.rejectRecoveryPrecondition(
              runId, rounds, tools, elapsed, reason, RecordSupport.now()))
            throw new IllegalStateException("无法确认恢复前置检查发生在任何 Agent 调用之前。");
          plans.settle(runId, RecordSupport.now());
          evidence.event(runId, EventType.FAILED, reason);
        });
  }

  private void createRun(
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
      Integer maxAgentTools,
      WorkflowTemplate workflow,
      CandidateRunPlan candidatePlan,
      boolean reviewRequired) {
    String time = RecordSupport.now();
    tx.executeWithoutResult(
        s -> {
          RunEntity run = new RunEntity();
          run.setId(id);
          run.setTaskId(taskId);
          run.setParentId(parent);
          run.setStatus(RunStatus.PENDING.name());
          run.setReason(null);
          run.setResult(null);
          run.setRounds(rounds);
          run.setTools(tools);
          run.setMaxRounds(maxRounds);
          run.setMaxTools(maxTools);
          run.setMaxSeconds(maxSeconds);
          run.setElapsedMs(elapsed);
          run.setCreatedAt(time);
          run.setUpdatedAt(time);
          run.setMaxPerTool(maxPerTool);
          run.setMaxReportedTokens(maxReportedTokens);
          run.setMaxIdenticalTools(maxIdenticalTools);
          run.setMaxAgentRounds(maxAgentRounds);
          run.setMaxAgentTools(maxAgentTools);
          runRows.save(run);
          // 计划与节点外键引用运行，事件写入还要锁运行行；先落库再继续。
          runRows.flush();
          if (workflow == null) {
            if (candidatePlan == null) plans.initialize(id, includeCoder);
            else plans.initializeFromCandidate(id, candidatePlan, tasks.taskDefinition(taskId), time);
          } else
            plans.initializeWorkflow(
                id, workflow, tasks.taskDefinition(taskId), reviewRequired, time);
        });
  }
}
