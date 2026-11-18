package dev.workbench.agent;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import dev.workbench.domain.EventType;
import dev.workbench.domain.ModelKind;
import dev.workbench.domain.ModelMode;
import dev.workbench.framework.agent.execution.AgentRuntime;
import dev.workbench.framework.model.ControlledModel;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.Model;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/** 验证分片不会提前结束模型调用，也不会丢失最终报告的用量。 */
class ControlledModelTest {
  private final Model delegate = mock(Model.class);
  private final AgentRuntime runtime = mock(AgentRuntime.class);
  private final AtomicReference<RuntimeException> failure = new AtomicReference<>();
  private final ControlledModel controlled =
      new ControlledModel(delegate, ModelMode.MOCK, runtime, failure);

  @Test
  void recordsOnceAfterAllChunksUsingLatestReportedUsage() {
    var first = ChatResponse.builder().build();
    var middle = ChatResponse.builder().usage(new ChatUsage(7, 3, 0)).build();
    var last = ChatResponse.builder().usage(new ChatUsage(9, 5, 0)).build();
    when(delegate.stream(anyList(), anyList(), isNull()))
        .thenReturn(Flux.just(first, middle, last));
    when(delegate.getModelName()).thenReturn("test");
    var responses = controlled.stream(List.of(), List.of(), null).collectList().block();
    assertEquals(List.of(first, middle, last), responses);
    verify(runtime).beforeModel(ModelKind.MOCK);
    verify(runtime, times(1)).modelResult(9, 5);
    verify(runtime, times(1)).event(eq(EventType.MODEL_FINISHED), anyString());
    assertNull(failure.get());
  }

  @Test
  void errorsAndEmptyStreamsNeverRecordSuccessfulResult() {
    when(delegate.stream(anyList(), anyList(), isNull()))
        .thenReturn(
            Flux.concat(
                Flux.just(ChatResponse.builder().build()),
                Flux.error(new IllegalStateException("failed"))),
            Flux.empty());
    assertThrows(
        RuntimeException.class,
        () -> controlled.stream(List.of(), List.of(), null).collectList().block());
    assertThrows(
        RuntimeException.class,
        () -> controlled.stream(List.of(), List.of(), null).collectList().block());
    verify(runtime, never()).modelResult(any(), any());
    verify(runtime, never()).event(eq(EventType.MODEL_FINISHED), anyString());
    assertNotNull(failure.get());
  }

  @Test
  void partialResponsePreventsRateLimitRejectionClassification() {
    var error = new IllegalStateException("已有分片后的错误");
    when(delegate.stream(anyList(), anyList(), isNull()))
        .thenReturn(Flux.concat(Flux.just(ChatResponse.builder().build()), Flux.error(error)));
    var restricted =
        new ControlledModel(delegate, ModelMode.MOCK, runtime, failure, failure -> true);
    assertSame(
        error,
        assertThrows(
            IllegalStateException.class,
            () -> restricted.stream(List.of(), List.of(), null).collectList().block()));
    assertNull(failure.get());
    verify(runtime, never()).modelResult(any(), any());
  }

  @Test
  void cancellationDoesNotPretendTheModelCompleted() {
    when(delegate.stream(anyList(), anyList(), isNull()))
        .thenReturn(Flux.concat(Flux.just(ChatResponse.builder().build()), Flux.never()));
    var subscription = controlled.stream(List.of(), List.of(), null).subscribe();
    subscription.dispose();
    verify(runtime, never()).modelResult(any(), any());
    verify(runtime, never()).event(eq(EventType.MODEL_FINISHED), anyString());
  }

  @Test
  void unknownUsageAndBudgetFailureAreNotMisrepresented() {
    when(delegate.stream(anyList(), anyList(), isNull()))
        .thenReturn(Flux.just(ChatResponse.builder().build()));
    controlled.stream(List.of(), List.of(), null).collectList().block();
    verify(runtime).modelResult(null, null);
    reset(runtime);
    doThrow(new IllegalStateException("budget")).when(runtime).modelResult(null, null);
    assertThrows(
        RuntimeException.class,
        () -> controlled.stream(List.of(), List.of(), null).collectList().block());
    verify(runtime, never()).event(eq(EventType.MODEL_FINISHED), anyString());
    assertNotNull(failure.get());
  }

  @Test
  void preservesUnderlyingModelCapabilities() {
    when(delegate.supportsNativeStructuredOutput()).thenReturn(true);
    when(delegate.supportsNativeStructuredOutputWithTools()).thenReturn(false);
    when(delegate.getContextWindowSize()).thenReturn(128_000);
    assertTrue(controlled.supportsNativeStructuredOutput());
    assertFalse(controlled.supportsNativeStructuredOutputWithTools());
    assertEquals(128_000, controlled.getContextWindowSize());
  }
}
