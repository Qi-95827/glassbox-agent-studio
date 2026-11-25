package dev.workbench.framework.control;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import dev.workbench.domain.EventType;
import dev.workbench.domain.RunSnapshot;
import dev.workbench.domain.RunStatus;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/**
 * 断点暂停的事件顺序。
 *
 * <p>反例对应 `F-10`：旧实现先 {@code paused.add(runId)} 再进边界，边界里的 {@code check()} 先落库通用 PAUSED
 * 事件并阻塞等待，断点原因只能等恢复后才写入，导致「先暂停、后写原因」的事件倒序。
 */
class RunControlBreakpointOrderingTest {
  private final RunControlStore repository = mock(RunControlStore.class);
  private final Set<String> paused = ConcurrentHashMap.newKeySet();
  private final Set<String> cancelled = ConcurrentHashMap.newKeySet();
  private final Object monitor = new Object();

  private RunControl control() {
    var run = mock(RunSnapshot.class);
    when(run.rounds()).thenReturn(0);
    when(run.tools()).thenReturn(0);
    when(run.elapsedMs()).thenReturn(0L);
    // Mockito 对包装类型也返回 0：限额必须显式置空，否则会被当成「上限 0」直接触发限额。
    when(run.maxRounds()).thenReturn(null);
    when(run.maxTools()).thenReturn(null);
    when(run.maxPerTool()).thenReturn(null);
    when(run.maxReportedTokens()).thenReturn(null);
    when(run.maxIdenticalTools()).thenReturn(null);
    when(run.maxSeconds()).thenReturn(null);
    when(run.maxAgentRounds()).thenReturn(null);
    when(run.maxAgentTools()).thenReturn(null);
    return new RunControl("run-1", run, repository, monitor, paused, cancelled, Map.of(), Map.of());
  }

  @Test
  void breakpointReasonIsWrittenBeforeGenericPauseEvent() throws Exception {
    var control = control();
    var finished = new CountDownLatch(1);
    var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
    var writer =
        new Thread(
            () -> {
              try {
                control.pauseAtBreakpoint(new Object(), "命中断点：审查前暂停");
              } catch (Throwable error) {
                failure.set(error);
              } finally {
                finished.countDown();
              }
            },
            "breakpoint-worker");
    writer.start();

    // 断点原因必须在暂停等待期间就已落库（旧实现要等恢复后才写）。
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    boolean written = false;
    while (!written && System.nanoTime() < deadline) {
      try {
        verify(repository, atLeastOnce())
            .event(eq("run-1"), eq(EventType.PAUSED), contains("命中断点"));
        written = true;
      } catch (AssertionError notYet) {
        Thread.sleep(10);
      }
    }
    assertNull(failure.get(), "暂停不应失败：" + failure.get());
    assertTrue(written, "断点原因必须在暂停期间写入，而不是恢复后补写");
    assertFalse(finished.await(100, TimeUnit.MILLISECONDS), "未恢复前执行线程应停留在暂停边界");

    paused.remove("run-1");
    synchronized (monitor) {
      monitor.notifyAll();
    }
    assertTrue(finished.await(5, TimeUnit.SECONDS), "恢复后必须结束暂停等待");

    InOrder order = inOrder(repository);
    order.verify(repository).event(eq("run-1"), eq(EventType.PAUSED), contains("命中断点"));
    order.verify(repository).event(eq("run-1"), eq(EventType.PAUSED), anyString());
    verify(repository, atLeastOnce())
        .update(eq("run-1"), eq(RunStatus.PAUSED), any(), any(), anyInt(), anyInt(), anyLong());
  }

  @Test
  void budgetPolicyExposesDeclaredLimits() {
    var policy = control().budgetPolicy(dev.workbench.domain.AgentRole.ANALYST, null);

    assertEquals(1, policy.version());
    assertNotNull(policy.limits());
    assertEquals(dev.workbench.domain.RunLimits.class, policy.limits().getClass());
  }
}
