package dev.workbench.framework.control;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import dev.workbench.domain.RunSnapshot;
import dev.workbench.domain.RunStatus;
import dev.workbench.domain.ToolKind;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * 并行节点绝不能击穿硬限额，人工审批等待不计入运行执行时间。
 *
 * <p>反例对应 `F-11`：旧实现先 `get()` 判限再 `incrementAndGet()`，并行节点可同时通过检查后一起递增； 审批等待期间 `elapsed()`
 * 持续增长，人工审批慢一点就会误触 `TIME_LIMIT`。
 */
class RunControlAtomicBudgetTest {
  private final RunControlStore repository = mock(RunControlStore.class);

  private RunControl control(Integer maxTools, Long previousMs) {
    var run = mock(RunSnapshot.class);
    when(run.rounds()).thenReturn(0);
    when(run.tools()).thenReturn(0);
    when(run.elapsedMs()).thenReturn(previousMs == null ? 0L : previousMs);
    when(run.status()).thenReturn(RunStatus.RUNNING);
    when(run.maxRounds()).thenReturn(null);
    when(run.maxTools()).thenReturn(maxTools);
    when(run.maxPerTool()).thenReturn(null);
    when(run.maxReportedTokens()).thenReturn(null);
    when(run.maxIdenticalTools()).thenReturn(null);
    when(run.maxSeconds()).thenReturn(null);
    when(run.maxAgentRounds()).thenReturn(null);
    when(run.maxAgentTools()).thenReturn(null);
    return new RunControl(
        "run-1",
        run,
        repository,
        new Object(),
        ConcurrentHashMap.newKeySet(),
        ConcurrentHashMap.newKeySet(),
        ConcurrentHashMap.newKeySet(),
        Map.of(),
        Map.of());
  }

  @Test
  void parallelToolCallsNeverExceedTheLimit() throws Exception {
    var control = control(5, null);
    int threads = 24;
    var start = new CountDownLatch(1);
    var done = new CountDownLatch(threads);
    var accepted = new AtomicInteger();
    var rejected = new AtomicInteger();

    for (int index = 0; index < threads; index++) {
      new Thread(
              () -> {
                try {
                  start.await();
                  control.beginTool(
                      "agent-" + Thread.currentThread().threadId(),
                      ToolKind.WORKSPACE_READ,
                      1,
                      "fp");
                  accepted.incrementAndGet();
                } catch (StopExecution limit) {
                  rejected.incrementAndGet();
                } catch (InterruptedException interrupted) {
                  Thread.currentThread().interrupt();
                } finally {
                  done.countDown();
                }
              })
          .start();
    }
    start.countDown();
    assertTrue(done.await(20, java.util.concurrent.TimeUnit.SECONDS));

    assertEquals(5, accepted.get(), "通过数必须恰好等于上限，不能击穿");
    assertEquals(threads - 5, rejected.get());
    assertEquals(5, control.tools());
  }

  @Test
  void approvalWaitIsExcludedFromElapsed() throws Exception {
    var control = control(null, 1_000L);

    control.beginApprovalWait();
    Thread.sleep(250);
    control.endApprovalWait();

    long elapsed = control.elapsed();
    assertTrue(elapsed < 1_200L, "审批等待必须从执行时长中扣除，实际 elapsed=" + elapsed);
    assertTrue(elapsed >= 1_000L, "基础时长仍然保留：" + elapsed);
  }

  @Test
  void nestedApprovalWaitIsCountedOnce() throws Exception {
    var control = control(null, 0L);

    control.beginApprovalWait();
    control.beginApprovalWait();
    Thread.sleep(200);
    control.endApprovalWait();
    control.endApprovalWait();
    long elapsed = control.elapsed();

    assertTrue(elapsed < 150L, "嵌套等待只按外层计量：" + elapsed);
  }

  @Test
  void toolLimitStillRejectsAfterBudgetExhausted() {
    var control = control(1, null);
    control.beginTool("agent-1", ToolKind.WORKSPACE_READ, 1, "fp");

    assertThrows(
        StopExecution.class, () -> control.beginTool("agent-2", ToolKind.WORKSPACE_READ, 1, "fp"));
    assertEquals(1, control.tools());
  }
}
