package dev.workbench.agent;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import dev.workbench.domain.ModelMode;
import dev.workbench.framework.agent.execution.AgentRuntime;
import dev.workbench.framework.context.CompactionRequest;
import dev.workbench.framework.context.CompactionResult;
import dev.workbench.framework.context.CompactionScope;
import dev.workbench.framework.context.ContextBreakdown;
import dev.workbench.infrastructure.agentscope.ControlledModel;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/**
 * 模型调用前压缩必须携带当前运行归属，且摘要用量回到运行台账。
 *
 * <p>反例对应 S-04 第 6 项：后台压缩线程丢失身份后只能回退 {@code LOCAL} 默认连接。
 */
class ControlledModelCompactionTest {
  private final Model delegate = mock(Model.class);
  private final AgentRuntime runtime = mock(AgentRuntime.class);
  private final AtomicReference<RuntimeException> failure = new AtomicReference<>();

  @Test
  void compactionCarriesRunScopeAndRoutesUsageToRuntime() {
    AtomicReference<CompactionRequest> captured = new AtomicReference<>();
    when(delegate.getContextWindowSize()).thenReturn(100_000);
    when(delegate.stream(anyList(), anyList(), any()))
        .thenReturn(Flux.just(ChatResponse.builder().build()));
    var controlled =
        new ControlledModel(
            delegate,
            ModelMode.USER,
            runtime,
            failure,
            error -> false,
            request -> {
              captured.set(request);
              return new CompactionResult(
                  request.messages(),
                  new ContextBreakdown(
                      100_000,
                      1_000,
                      1_000,
                      Map.of("HISTORY", 1_000),
                      dev.workbench.framework.context.CompactionTier.L0_OBSERVE_ONLY,
                      0,
                      0,
                      List.of(),
                      "",
                      List.of(),
                      "",
                      ""));
            },
            new CompactionScope("run-42", "USER_TEST", "conn-7", null));

    controlled.stream(
            List.of(Msg.builder().role(MsgRole.USER).textContent("你好").build()), List.of(), null)
        .collectList()
        .block();

    assertNotNull(captured.get(), "上下文压缩必须被调用");
    assertEquals("run-42", captured.get().scope().runId());
    assertEquals("USER_TEST", captured.get().scope().ownerScopeKey());
    assertEquals("conn-7", captured.get().scope().modelConnectionId());

    // 摘要等后台调用的用量必须回到当前运行台账，而不是被静默丢弃。
    captured.get().scope().usage().record(12, 8);
    verify(runtime).auxiliaryModelUsage(12, 8);
  }

  @Test
  void budgetStopFromCompactionIsNotSwallowed() {
    when(delegate.getContextWindowSize()).thenReturn(100_000);
    var stop =
        new dev.workbench.framework.control.StopExecution(
            dev.workbench.domain.StopCause.COST_LIMIT, "摘要超预算");
    var controlled =
        new ControlledModel(
            delegate,
            ModelMode.USER,
            runtime,
            failure,
            error -> false,
            request -> {
              throw stop;
            },
            new CompactionScope("run-1", "USER_A", "conn-1", null));

    var thrown =
        assertThrows(
            dev.workbench.framework.control.StopExecution.class,
            () ->
                controlled.stream(
                        List.of(Msg.builder().role(MsgRole.USER).textContent("你好").build()),
                        List.of(),
                        null)
                    .collectList()
                    .block());
    assertSame(stop, thrown);
    verify(delegate, never()).stream(anyList(), anyList(), any());
  }

  @Test
  void legacyConstructorWithoutRunKeepsEmptyScope() {
    // 旧构造器（未绑定运行）仍可工作，但压缩上下文为空，不会误加到他账号。
    var legacy = new ControlledModel(delegate, ModelMode.MOCK, runtime, failure);

    assertEquals(CompactionScope.NONE, legacy.compactionScope());
    assertFalse(legacy.compactionScope().hasConnection());
    assertNull(legacy.compactionScope().runId());
  }
}
