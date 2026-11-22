package dev.workbench.observability;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.workbench.domain.EventType;
import dev.workbench.domain.RunStatus;
import dev.workbench.framework.event.EventPage;
import dev.workbench.framework.event.RunEvent;
import dev.workbench.framework.event.RunEventPersisted;
import dev.workbench.store.RunStateStore;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TaskScheduler;

/** SSE 断点补发、连接上限与终态关闭。 */
class RunStreamBrokerTest {
  private final EventQueryService events = mock(EventQueryService.class);
  private final RunStateStore runs = mock(RunStateStore.class);
  private final TaskScheduler scheduler = mock(TaskScheduler.class);

  @Test
  void subscribeReplaysFromLastEventIdAndCapsConnections() {
    String runId = UUID.randomUUID().toString();
    var snapshot = mock(dev.workbench.domain.RunSnapshot.class);
    when(snapshot.status()).thenReturn(RunStatus.RUNNING);
    when(runs.runSnapshot(runId)).thenReturn(snapshot);
    when(events.page(eq(runId), anyLong(), eq(200))).thenReturn(new EventPage(List.of(), 42));
    when(scheduler.scheduleAtFixedRate(any(Runnable.class), any(java.time.Duration.class)))
        .thenReturn(mock(ScheduledFuture.class));
    var broker = new RunStreamBroker(events, runs, new ObjectMapper(), scheduler);

    for (int i = 0; i < 8; i++) assertNotNull(broker.subscribe(runId, 42L));
    verify(events, times(8)).page(runId, 42L, 200);
    assertThrows(IllegalStateException.class, () -> broker.subscribe(runId, 42L));
  }

  @Test
  void persistedEventIsForwardedToSubscribers() {
    String runId = UUID.randomUUID().toString();
    var snapshot = mock(dev.workbench.domain.RunSnapshot.class);
    when(snapshot.status()).thenReturn(RunStatus.RUNNING);
    when(runs.runSnapshot(runId)).thenReturn(snapshot);
    when(events.page(eq(runId), anyLong(), eq(200))).thenReturn(new EventPage(List.of(), 0));
    when(scheduler.scheduleAtFixedRate(any(Runnable.class), any(java.time.Duration.class)))
        .thenReturn(mock(ScheduledFuture.class));
    var broker = new RunStreamBroker(events, runs, new ObjectMapper(), scheduler);
    assertNotNull(broker.subscribe(runId, 0L));
    broker.onPersisted(
        new RunEventPersisted(
            new RunEvent(
                1,
                1,
                UUID.fromString(runId),
                EventType.RUN_STARTED,
                "开始",
                Instant.now(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null)));
    broker.close(runId);
  }
}
