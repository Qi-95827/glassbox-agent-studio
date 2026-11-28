package dev.workbench.orchestration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import dev.workbench.domain.RunSnapshot;
import dev.workbench.domain.RunStatus;
import dev.workbench.store.RunCreationStore;
import dev.workbench.store.RunEvidenceRecordService;
import dev.workbench.store.RunStateStore;
import dev.workbench.workspace.RunHandoverService;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * 断点继续遇到执行器队列拒绝时的恢复语义：
 * 首次被拒必须恢复 {@code yielded} 标记并保留已有轮次/工具数/耗时，二次继续必须能够成功重新派发。
 */
class RunCommandResumeRejectionTest {

  @Test
  void rejectedResumeRestoresYieldedFlagPreservesCountersAndAllowsRetry() {
    String runId = UUID.randomUUID().toString();
    String taskId = UUID.randomUUID().toString();
    var snapshot =
        new RunSnapshot(
            UUID.fromString(runId),
            UUID.fromString(taskId),
            null,
            RunStatus.PAUSED,
            4,
            7,
            20,
            40,
            null,
            null,
            null,
            300,
            12_345L,
            null,
            null);

    RunStateStore runs = mock(RunStateStore.class);
    RunCreationStore creation = mock(RunCreationStore.class);
    RunEvidenceRecordService evidence = mock(RunEvidenceRecordService.class);
    RunHandoverService handovers = mock(RunHandoverService.class);
    WorkflowScheduler workflows = mock(WorkflowScheduler.class);
    ExecutorService executor = mock(ExecutorService.class);
    RunService owner = mock(RunService.class);
    RunExecutionEngine engine = mock(RunExecutionEngine.class);

    when(runs.runSnapshot(runId)).thenReturn(snapshot);
    when(handovers.resumeBlockReason(runId)).thenReturn(null);

    var submitAttempts = new AtomicInteger();
    when(executor.submit(any(Runnable.class)))
        .thenAnswer(
            inv -> {
              if (submitAttempts.incrementAndGet() == 1) {
                throw new RejectedExecutionException("队列已满");
              }
              ((Runnable) inv.getArgument(0)).run();
              return CompletableFuture.completedFuture(null);
            });

    var locks = new RunLockRegistry();
    var dispatcher = new RunDispatchService(runs, creation, executor, owner);
    dispatcher.setEngine(engine);
    var commands = new RunCommandService(locks, runs, evidence, handovers, workflows, dispatcher);
    commands.yielded().add(runId);

    // 第一次继续：执行器拒绝
    commands.resume(runId);

    assertEquals(1, submitAttempts.get(), "第一次应尝试派发");
    assertTrue(commands.yielded().contains(runId), "队列拒绝后必须恢复 yielded 标记，否则后续无法再次派发");
    verify(runs)
        .update(
            eq(runId),
            eq(RunStatus.PAUSED),
            contains("未能加入执行队列"),
            isNull(),
            eq(4),
            eq(7),
            eq(12_345L));
    verify(engine, never()).executeResume(anyString());

    // 第二次继续：队列恢复空闲，必须成功重新派发并执行
    commands.resume(runId);

    assertEquals(2, submitAttempts.get(), "第二次继续必须重新提交执行器");
    assertFalse(commands.yielded().contains(runId), "成功派发后 yielded 标记应已消费");
    verify(engine, times(1)).executeResume(runId);
  }

  /**
   * 确定性并发交错反例：resume() 释放分段锁后、执行器 submit() 拒绝前，cancel() 恰好到达。
   * 此时已无工作线程可消费 cancelled，拒绝回滚必须在持锁期间识别已到达的取消请求并直接终结为 USER_CANCELLED，
   * 绝不能把运行退回无人处理的 PAUSED。
   */
  @Test
  void cancelInterleavedWithResumeRejectionFinishesRunAsCancelled() {
    String runId = UUID.randomUUID().toString();
    String taskId = UUID.randomUUID().toString();
    var snapshot =
        new RunSnapshot(
            UUID.fromString(runId),
            UUID.fromString(taskId),
            null,
            RunStatus.PAUSED,
            4,
            7,
            20,
            40,
            null,
            null,
            null,
            300,
            12_345L,
            null,
            null);

    RunStateStore runs = mock(RunStateStore.class);
    RunCreationStore creation = mock(RunCreationStore.class);
    RunEvidenceRecordService evidence = mock(RunEvidenceRecordService.class);
    RunHandoverService handovers = mock(RunHandoverService.class);
    WorkflowScheduler workflows = mock(WorkflowScheduler.class);
    ExecutorService executor = mock(ExecutorService.class);
    RunService owner = mock(RunService.class);
    RunExecutionEngine engine = mock(RunExecutionEngine.class);

    when(runs.runSnapshot(runId)).thenReturn(snapshot);
    when(handovers.resumeBlockReason(runId)).thenReturn(null);

    var locks = new RunLockRegistry();
    var dispatcher = new RunDispatchService(runs, creation, executor, owner);
    dispatcher.setEngine(engine);
    var commands = new RunCommandService(locks, runs, evidence, handovers, workflows, dispatcher);
    commands.yielded().add(runId);

    // 固定交错窗口：resume() 移除了 yielded 并释放锁后，submit() 内模拟并发 cancel() 先拿到锁，再抛出拒绝异常。
    when(executor.submit(any(Runnable.class)))
        .thenAnswer(
            inv -> {
              commands.cancel(runId);
              throw new RejectedExecutionException("队列已满");
            });

    commands.resume(runId);

    var stopCaptor = org.mockito.ArgumentCaptor.forClass(dev.workbench.store.StopCompletion.class);
    verify(runs, times(1)).finishStoppedRun(stopCaptor.capture());
    var stop = stopCaptor.getValue();
    assertEquals(UUID.fromString(runId), stop.runId());
    assertEquals(dev.workbench.domain.StopCause.USER_CANCELLED, stop.cause());
    assertEquals(4, stop.rounds());
    assertEquals(7, stop.tools());
    assertEquals(12_345L, stop.elapsedMs());
    verify(runs, never()).update(anyString(), eq(RunStatus.PAUSED), any(), any(), anyInt(), anyInt(), anyLong());
    assertFalse(commands.yielded().contains(runId), "终结后 yielded 必须已清空");
    assertFalse(commands.cancelled().contains(runId), "终结后 cancelled 必须已清空");
    verify(workflows, times(1)).clearReleasedBreakpoints(runId);
  }
}
