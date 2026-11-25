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
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/**
 * 断点暂停：落库顺序正确且立即让出线程。
 *
 * <p>反例对应 `F-08`/`F-10`：旧实现先 `paused.add` 再进边界，边界里的 `check()` 先落通用 PAUSED 并阻塞等待，
 * 断点原因要等恢复后才写入；且断点会长期占用固定大小的执行线程池。
 */
class RunControlBreakpointOrderingTest {
  private final RunControlStore repository = mock(RunControlStore.class);
  private final Set<String> paused = ConcurrentHashMap.newKeySet();
  private final Set<String> cancelled = ConcurrentHashMap.newKeySet();
  private final Set<String> yielded = ConcurrentHashMap.newKeySet();
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
    return new RunControl(
        "run-1", run, repository, monitor, paused, cancelled, yielded, Map.of(), Map.of());
  }

  @Test
  void breakpointWritesStateAndReasonThenYieldsImmediately() {
    var control = control();

    var yield =
        assertThrows(PauseYield.class, () -> control.pauseAtBreakpoint(new Object(), "命中断点：审查前暂停"));

    assertEquals("run-1", yield.runId());
    assertTrue(paused.contains("run-1"), "暂停标记必须保留，等待 /resume 清除");
    assertTrue(yielded.contains("run-1"), "必须记录让出标记，恢复时才重新派发");
    InOrder order = inOrder(repository);
    order
        .verify(repository)
        .update(
            eq("run-1"), eq(RunStatus.PAUSED), isNull(), isNull(), anyInt(), anyInt(), anyLong());
    order.verify(repository).event(eq("run-1"), eq(EventType.PAUSED), contains("命中断点"));
    verify(repository, atLeastOnce())
        .update(
            eq("run-1"), eq(RunStatus.PAUSED), isNull(), isNull(), anyInt(), anyInt(), anyLong());
  }

  @Test
  void budgetPolicyExposesDeclaredLimits() {
    var policy = control().budgetPolicy(dev.workbench.domain.AgentRole.ANALYST, null);

    assertEquals(1, policy.version());
    assertNotNull(policy.limits());
    assertEquals(dev.workbench.domain.RunLimits.class, policy.limits().getClass());
  }
}
