package dev.workbench.orchestration;

import dev.workbench.domain.CheckpointSnapshot;
import dev.workbench.domain.CheckpointStage;
import dev.workbench.domain.EventType;
import dev.workbench.domain.RunSnapshot;
import dev.workbench.domain.RunStatus;
import dev.workbench.domain.StopCause;
import dev.workbench.domain.TaskDefinition;
import dev.workbench.store.RunCreationStore;
import dev.workbench.store.RunEvidenceRecordService;
import dev.workbench.store.RunStateStore;
import dev.workbench.store.StopCompletion;
import dev.workbench.store.TaskRecordService;
import dev.workbench.task.CostService;
import dev.workbench.workspace.RunHandoverService;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 运行控制命令与进程内的暂停/取消集合；检查点恢复也在这里完成前置校验。 */
final class RunCommandService {
  private final Object stateLock;
  private final RunStateStore runs;
  private final RunCreationStore creation;
  private final TaskRecordService tasks;
  private final RunEvidenceRecordService evidence;
  private final RunHandoverService handovers;
  private final WorkflowScheduler workflows;
  private final CostService costs;
  private final RunCheckpointSupport checkpoints;
  private final RunDispatchService dispatcher;
  private final Set<String> cancelled = ConcurrentHashMap.newKeySet();
  private final Set<String> paused = ConcurrentHashMap.newKeySet();
  private final Set<String> yielded = ConcurrentHashMap.newKeySet();
  private final Map<String, Object> pauseMonitors = new ConcurrentHashMap<>();

  RunCommandService(
      Object stateLock,
      RunStateStore runs,
      RunCreationStore creation,
      TaskRecordService tasks,
      RunEvidenceRecordService evidence,
      RunHandoverService handovers,
      WorkflowScheduler workflows,
      CostService costs,
      RunCheckpointSupport checkpoints,
      RunDispatchService dispatcher) {
    this.stateLock = stateLock;
    this.runs = runs;
    this.creation = creation;
    this.tasks = tasks;
    this.evidence = evidence;
    this.handovers = handovers;
    this.workflows = workflows;
    this.costs = costs;
    this.checkpoints = checkpoints;
    this.dispatcher = dispatcher;
  }

  Set<String> cancelled() {
    return cancelled;
  }

  /** 因断点让出线程的运行集合；恢复时据此决定是否重新派发。 */
  Set<String> yielded() {
    return yielded;
  }

  Set<String> paused() {
    return paused;
  }

  Object pauseMonitor(String id) {
    return pauseMonitors.computeIfAbsent(id, key -> new Object());
  }

  void clearControl(String id) {
    cancelled.remove(id);
    paused.remove(id);
    pauseMonitors.remove(id);
  }

  /** 排队中的任务立即终止；运行中的任务通过信号协作取消。 */
  void cancel(String id) {
    synchronized (stateLock) {
      RunSnapshot run = runs.runSnapshot(id);
      if (!run.status().canCancel()) throw new IllegalStateException("运行已结束，不能取消。");
      evidence.event(id, EventType.CANCEL_REQUESTED, "用户请求取消。");
      if (run.status() == RunStatus.PENDING) {
        runs.finishStoppedRun(
            new StopCompletion(
                java.util.UUID.fromString(id),
                null,
                StopCause.USER_CANCELLED,
                "用户取消了排队中的运行，尚未启动 Agent。",
                run.rounds(),
                run.tools(),
                run.elapsedMs(),
                null));
      } else {
        cancelled.add(id);
        Object monitor = pauseMonitors.get(id);
        if (monitor != null)
          synchronized (monitor) {
            monitor.notifyAll();
          }
      }
    }
  }

  /** 请求在下一个协作检查点暂停；在途模型请求可能仍会结束并产生费用。 */
  void pause(String id) {
    synchronized (stateLock) {
      RunSnapshot run = runs.runSnapshot(id);
      if (run.status() != RunStatus.RUNNING) throw new IllegalStateException("只有运行中的任务可以请求暂停。");
      if (paused.add(id))
        evidence.event(id, EventType.PAUSE_REQUESTED, "已请求暂停，等待安全检查点；在途调用可能继续结束。");
    }
  }

  /** 恢复当前进程内的暂停；进程重启后的恢复仍使用检查点。 */
  void resume(String id) {
    boolean resumeDispatch = false;
    synchronized (stateLock) {
      RunSnapshot run = runs.runSnapshot(id);
      if (run.status() != RunStatus.PAUSED
          && !(run.status() == RunStatus.RUNNING && paused.contains(id)))
        throw new IllegalStateException("该任务没有可继续的暂停请求。");
      String handoverBlock = handovers.resumeBlockReason(id);
      if (handoverBlock != null) throw new IllegalStateException(handoverBlock);
      paused.remove(id);
      resumeDispatch = yielded.remove(id);
      evidence.event(id, EventType.RESUMED, "用户请求继续执行。");
      Object monitor = pauseMonitors.get(id);
      if (monitor != null)
        synchronized (monitor) {
          monitor.notifyAll();
        }
    }
    // 断点暂停已让出工作线程，必须重新派发；普通暂停只是唤醒仍在等待的线程，不重复派发。
    if (resumeDispatch && dispatcher != null) dispatcher.dispatchResume(id);
  }

  /** 从有效快照创建新运行，不覆盖历史。此处的同步只覆盖本进程； 外部副作用和分布式并发恢复尚未实现。 */
  String recover(String oldId, String checkpointId) {
    synchronized (stateLock) {
      RunSnapshot original = runs.runSnapshot(oldId);
      if (original.status() != RunStatus.INTERRUPTED)
        throw new IllegalStateException("只有中断的运行可以恢复。");
      if (workflows.plan(oldId) != null) {
        String nextId = dev.workbench.domain.WorkbenchIds.newId();
        workflows.recover(
            oldId, java.util.UUID.fromString(checkpointId), java.util.UUID.fromString(nextId));
        dispatcher.dispatchRecovery(nextId, CheckpointStage.BEFORE_TOOL, null);
        return nextId;
      }
      TaskDefinition task = tasks.taskDefinition(original.taskId().toString());
      CheckpointSnapshot checkpoint = evidence.checkedCheckpoint(checkpointId);
      if (!checkpoint.runId().equals(original.id()))
        throw new IllegalArgumentException("检查点不属于该运行。");
      if (task.bugId() != null || task.writeEnabled())
        throw new IllegalStateException("写入或 Bug 任务尚不支持安全恢复，请先人工检查外部状态。");
      if (task.workspaceId() != null) {
        if (task.memoryEnabled()
            || !Integer.valueOf(6).equals(checkpoint.version())
            || checkpoint.stage() != CheckpointStage.BEFORE_REVIEW)
          throw new IllegalStateException("当前工作区任务或检查点不支持安全恢复。");
        checkpoints.requireUnchangedWorkspace(
            task, evidence.workspaceReviewPayload(checkpoint.toolResult()));
      }
      if (runs.hasRecovery(oldId)) throw new IllegalStateException("此运行已经发起恢复。");

      String nextId = dev.workbench.domain.WorkbenchIds.newId();
      creation.createRecoveryRun(java.util.UUID.fromString(nextId), original, checkpoint);
      if (costs != null) costs.inheritLimit(oldId, nextId);
      dispatcher.dispatchRecovery(nextId, checkpoint.stage(), checkpoint.toolResult());
      return nextId;
    }
  }
}
