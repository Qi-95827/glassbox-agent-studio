package dev.workbench.orchestration;

import dev.workbench.domain.CheckpointStage;
import dev.workbench.domain.RunSnapshot;
import dev.workbench.domain.RunStatus;
import dev.workbench.domain.StopCause;
import dev.workbench.domain.TaskDefinition;
import dev.workbench.framework.control.RunControl;
import dev.workbench.framework.workflow.plan.WorkflowPlan;
import dev.workbench.store.RunRecordService;
import dev.workbench.store.StopCompletion;

/** 运行执行入口：认领待运行、构建运行控制、选择动态计划或直接执行路径。 */
final class RunExecutionEngine {
  private final RunRecordService repository;
  private final WorkflowScheduler workflows;
  private final RunCommandService commands;
  private final DirectRunExecutor direct;
  private final Object stateLock;

  RunExecutionEngine(
      RunRecordService repository,
      WorkflowScheduler workflows,
      RunCommandService commands,
      DirectRunExecutor direct,
      Object stateLock) {
    this.repository = repository;
    this.workflows = workflows;
    this.commands = commands;
    this.direct = direct;
    this.stateLock = stateLock;
  }

  void execute(String id, CheckpointStage stage, String previousResult, boolean recovery) {
    Prepared prepared = prepare(id);
    if (prepared == null) return;
    RunControl control =
        new RunControl(
            id,
            prepared.run(),
            repository,
            commands.pauseMonitor(id),
            commands.paused(),
            commands.cancelled(),
            prepared.task().roleBudgets(),
            prepared.task().toolBudgets());
    if (prepared.workflow() != null) {
      try {
        workflows.execute(id, prepared.workflow(), prepared.task(), control, stateLock);
      } finally {
        commands.clearControl(id);
      }
      return;
    }
    var state =
        new DirectRunExecutor.ExecutionState(
            id,
            prepared.run(),
            prepared.task(),
            control,
            stage,
            previousResult,
            recovery);
    state.agentId = dev.workbench.domain.WorkbenchIds.newId();
    direct.execute(state);
  }

  private Prepared prepare(String id) {
    RunSnapshot run;
    TaskDefinition task;
    WorkflowPlan workflow;
    // 与排队取消共享同一把锁，保证已取消的任务不会再次被启动。
    synchronized (stateLock) {
      run = repository.runSnapshot(id);
      if (run.status() != RunStatus.PENDING) return null;
      try {
        task = repository.taskDefinition(run.taskId().toString());
        workflow = workflows.plan(id);
      } catch (RuntimeException invalid) {
        repository.finishStoppedRun(
            new StopCompletion(
                java.util.UUID.fromString(id),
                null,
                StopCause.UNCLASSIFIED_FAILURE,
                "任务配置无法确认，未启动 Agent。",
                run.rounds(),
                run.tools(),
                run.elapsedMs(),
                null));
        return null;
      }
      if (workflow != null && workflow.approvedAt() == null) return null;
      repository.update(
          id, RunStatus.RUNNING, null, null, run.rounds(), run.tools(), run.elapsedMs());
    }
    return new Prepared(run, task, workflow);
  }

  private record Prepared(RunSnapshot run, TaskDefinition task, WorkflowPlan workflow) {}
}
