package dev.workbench.orchestration;

import dev.workbench.domain.CheckpointStage;
import dev.workbench.domain.RunSnapshot;
import dev.workbench.domain.RunStatus;
import dev.workbench.domain.StopCause;
import dev.workbench.domain.TaskDefinition;
import dev.workbench.framework.control.RunControl;
import dev.workbench.framework.workflow.plan.WorkflowPlan;
import dev.workbench.store.RunStateStore;
import dev.workbench.store.StopCompletion;
import dev.workbench.store.TaskRecordService;

/** 运行执行入口：认领待运行、构建运行控制并交给唯一的工作流调度器。 */
final class RunExecutionEngine {
  private final RunStateStore runs;
  private final TaskRecordService tasks;
  private final dev.workbench.framework.control.RunControlStore controls;
  private final WorkflowScheduler workflows;
  private final RunCommandService commands;
  private final RunLockRegistry locks;

  /** 历史无计划运行的统一结算说明：遗留直跑轨已下线，这类运行不再执行。 */
  static final String LEGACY_RUN_WITHOUT_PLAN_REASON =
      "该运行创建于遗留直线执行轨道且没有工作流计划；遗留轨道已下线，本次未启动任何 Agent，请重新创建任务使用标准工作流引擎。";

  RunExecutionEngine(
      RunStateStore runs,
      TaskRecordService tasks,
      dev.workbench.framework.control.RunControlStore controls,
      WorkflowScheduler workflows,
      RunCommandService commands,
      RunLockRegistry locks) {
    this.runs = runs;
    this.tasks = tasks;
    this.controls = controls;
    this.workflows = workflows;
    this.commands = commands;
    this.locks = locks;
  }

  void execute(String id, CheckpointStage stage, String previousResult, boolean recovery) {
    Prepared prepared = prepare(id);
    if (prepared == null) return;
    // 必须共享命令服务的 yielded 集合：否则断点让出后 /resume 永远判定不到「线程已退出」，运行卡死在 PAUSED。
    RunControl control =
        new RunControl(
            id,
            prepared.run(),
            controls,
            commands.pauseMonitor(id),
            commands.paused(),
            commands.cancelled(),
            commands.yielded(),
            prepared.task().roleBudgets(),
            prepared.task().toolBudgets());
    try {
      // 同一运行恒用同一把分段锁，不同运行按槽位并发。
      workflows.execute(id, prepared.workflow(), prepared.task(), control, locks.lockFor(id));
    } finally {
      commands.clearControl(id);
    }
  }

  /**
   * 恢复因断点让出线程的运行：只在状态仍为 {@code PAUSED} 时重新进入调度，继续认领剩余节点。
   *
   * <p>让出发生在未认领任何节点的边界，因此这里不会出现 {@code RUNNING} 认领孤儿；已完成节点由计划状态 天然跳过，不会被重复执行。
   */
  void executeResume(String id) {
    RunSnapshot run;
    TaskDefinition task;
    WorkflowPlan plan;
    // 与取消/恢复共享同一把锁：原子复核 PAUSED 并切回 RUNNING，防止并发双 /resume 重复执行。
    synchronized (locks.lockFor(id)) {
      run = runs.runSnapshot(id);
      if (run.status() != dev.workbench.domain.RunStatus.PAUSED) return;
      if (commands.cancelled().contains(id)) {
        // 派发窗口内用户已请求取消：直接进入取消终态，不切回 RUNNING 也不启动任何节点。
        commands.finishCancelledWithoutWorker(id, run, "用户取消了已暂停（断点让出线程）的运行。");
        return;
      }
      plan = workflows.plan(id);
      if (plan == null) return;
      try {
        task = workflows.effectiveTask(id, tasks.taskDefinition(run.taskId().toString()));
      } catch (RuntimeException invalid) {
        return;
      }
      if (plan.approvedAt() == null) return;
      runs.update(
          id,
          dev.workbench.domain.RunStatus.RUNNING,
          null,
          null,
          run.rounds(),
          run.tools(),
          run.elapsedMs());
      // 让 RunControl 以真实状态初始化，后续 check() 才能在暂停解除时正确回写 RUNNING。
      run =
          new RunSnapshot(
              run.id(),
              run.taskId(),
              run.parentId(),
              dev.workbench.domain.RunStatus.RUNNING,
              run.rounds(),
              run.tools(),
              run.maxRounds(),
              run.maxTools(),
              run.maxPerTool(),
              run.maxReportedTokens(),
              run.maxIdenticalTools(),
              run.maxSeconds(),
              run.elapsedMs(),
              run.maxAgentRounds(),
              run.maxAgentTools());
    }
    RunControl control =
        new RunControl(
            id,
            run,
            controls,
            commands.pauseMonitor(id),
            commands.paused(),
            commands.cancelled(),
            commands.yielded(),
            task.roleBudgets(),
            task.toolBudgets());
    try {
      workflows.execute(id, plan, task, control, locks.lockFor(id), true);
    } finally {
      commands.clearControl(id);
    }
  }

  private Prepared prepare(String id) {
    RunSnapshot run;
    TaskDefinition task;
    WorkflowPlan workflow;
    // 与排队取消共享同一把锁，保证已取消的任务不会再次被启动。
    synchronized (locks.lockFor(id)) {
      run = runs.runSnapshot(id);
      if (run.status() != RunStatus.PENDING) return null;
      try {
        task = workflows.effectiveTask(id, tasks.taskDefinition(run.taskId().toString()));
        workflow = workflows.plan(id);
      } catch (RuntimeException invalid) {
        runs.finishStoppedRun(
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
      if (workflow == null) {
        // 收口包 G-2：历史旧库里无工作流计划的 PENDING 记录不再直接执行，安全结算为失败并说明原因。
        runs.finishStoppedRun(
            new StopCompletion(
                java.util.UUID.fromString(id),
                null,
                StopCause.UNCLASSIFIED_FAILURE,
                LEGACY_RUN_WITHOUT_PLAN_REASON,
                run.rounds(),
                run.tools(),
                run.elapsedMs(),
                null));
        return null;
      }
      if (workflow.approvedAt() == null) return null;
      runs.update(id, RunStatus.RUNNING, null, null, run.rounds(), run.tools(), run.elapsedMs());
    }
    return new Prepared(run, task, workflow);
  }

  private record Prepared(RunSnapshot run, TaskDefinition task, WorkflowPlan workflow) {}
}
