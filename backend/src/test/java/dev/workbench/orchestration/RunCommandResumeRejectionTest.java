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
}
