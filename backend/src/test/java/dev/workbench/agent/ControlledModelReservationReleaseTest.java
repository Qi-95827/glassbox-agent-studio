package dev.workbench.agent;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.workbench.domain.ModelMode;
import dev.workbench.framework.agent.execution.AgentRuntime;
import dev.workbench.infrastructure.agentscope.ControlledModel;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.Model;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/**
 * 模型调用失败/取消时必须释放费用预留。
 *
 * <p>反例对应 `F-12`：预留只在正常返回时结算，一次失败或取消就永久占用运行费用额度，累计几次后正常调用 也会被预算拒绝。
 */
class ControlledModelReservationReleaseTest {
  private final Model delegate = mock(Model.class);
  private final AgentRuntime runtime = mock(AgentRuntime.class);

  private ControlledModel model() {
    when(delegate.getContextWindowSize()).thenReturn(0);
    return new ControlledModel(
        delegate, ModelMode.USER, runtime, new AtomicReference<>(), error -> false);
  }

  private static List<Msg> messages() {
    return List.of(Msg.builder().role(MsgRole.USER).textContent("你好").build());
  }

  @Test
  void failureReleasesReservation() {
    when(delegate.stream(anyList(), anyList(), any()))
        .thenReturn(Flux.error(new IllegalStateException("上游 500")));

    assertThrows(
        IllegalStateException.class,
        () -> model().stream(messages(), List.of(), null).collectList().block());

    verify(runtime).modelFailed();
  }

  @Test
  void cancellationReleasesReservation() {
    when(delegate.stream(anyList(), anyList(), any())).thenReturn(Flux.never());

    var subscription = model().stream(messages(), List.of(), null).subscribe();
    subscription.dispose();

    verify(runtime).modelFailed();
  }

  @Test
  void successfulCallDoesNotRelease() {
    when(delegate.stream(anyList(), anyList(), any()))
        .thenReturn(Flux.just(io.agentscope.core.model.ChatResponse.builder().build()));

    model().stream(messages(), List.of(), null).collectList().block();

    verify(runtime, never()).modelFailed();
    verify(runtime).modelResult(any(), any());
  }
}
