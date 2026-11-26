package dev.workbench.observability;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.workbench.framework.event.RunEvent;
import dev.workbench.framework.event.RunEventPersisted;
import dev.workbench.framework.event.RunStreamTransient;
import dev.workbench.store.RunStateStore;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 运行级 SSE 广播。
 *
 * <p>持久化事件使用运行内 {@code run_seq} 作为 SSE id；重连时按 {@code Last-Event-ID} 补发遗漏事件，
 * 15 秒发送注释心跳，运行终态后自动关闭。瞬态模型增量只推送、不落库。
 */
@Component
public class RunStreamBroker {
  private static final int MAX_CONNECTIONS_PER_RUN = 8;
  private static final Duration HEARTBEAT = Duration.ofSeconds(15);
  private static final long EMITTER_TIMEOUT_MS = 30 * 60 * 1000L;

  private final EventQueryService events;
  private final RunStateStore runs;
  private final ObjectMapper json;
  private final TaskScheduler scheduler;
  private final ConcurrentHashMap<String, CopyOnWriteArrayList<Subscription>> subscribers =
      new ConcurrentHashMap<>();

  public RunStreamBroker(
      EventQueryService events,
      RunStateStore runs,
      ObjectMapper json,
      @Qualifier("streamTaskScheduler") TaskScheduler scheduler) {
    this.events = events;
    this.runs = runs;
    this.json = json;
    this.scheduler = scheduler;
  }

  /** 建立订阅并按 Last-Event-ID 补发遗漏的持久化事件。 */
  public SseEmitter subscribe(String runId, Long lastEventId) {
    var list = subscribers.computeIfAbsent(runId, ignored -> new CopyOnWriteArrayList<>());
    if (list.size() >= MAX_CONNECTIONS_PER_RUN)
      throw new IllegalStateException("单运行实时连接数已达到上限。");
    var emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
    var subscription = new Subscription(emitter);
    list.add(subscription);
    subscription.heartbeat =
        scheduler.scheduleAtFixedRate(
            () -> {
              try {
                emitter.send(SseEmitter.event().comment("keepalive"));
              } catch (IOException | IllegalStateException dead) {
                remove(runId, subscription);
              }
            },
            HEARTBEAT);
    emitter.onCompletion(() -> remove(runId, subscription));
    emitter.onTimeout(() -> remove(runId, subscription));
    emitter.onError(error -> remove(runId, subscription));

    long cursor = lastEventId == null || lastEventId < 0 ? 0 : lastEventId;
    while (true) {
      var page = events.page(runId, cursor, 200);
      for (var event : page.events()) sendEvent(emitter, event);
      cursor = page.nextCursor();
      if (page.events().size() < 200) break;
    }
    if (!runs.runSnapshot(runId).status().canCancel()) {
      remove(runId, subscription);
      emitter.complete();
    }
    return emitter;
  }

  @EventListener
  public void onPersisted(RunEventPersisted persisted) {
    if (persisted == null || persisted.event() == null) return;
    var event = persisted.event();
    var list = subscribers.get(event.runId().toString());
    if (list == null) return;
    for (var subscription : list) sendEvent(subscription.emitter, event);
  }

  @EventListener
  public void onTransient(RunStreamTransient transientEvent) {
    if (transientEvent == null) return;
    var list = subscribers.get(transientEvent.runId().toString());
    if (list == null) return;
    try {
      String payload =
          json.writeValueAsString(
              new TransientPayload(
                  transientEvent.kind(),
                  transientEvent.text(),
                  transientEvent.nodeId(),
                  transientEvent.detail(),
                  transientEvent.occurredAt().toString()));
      for (var subscription : list) {
        try {
          subscription.emitter.send(
              SseEmitter.event()
                  .name(transientEvent.kind())
                  .data(payload, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException dead) {
          remove(transientEvent.runId().toString(), subscription);
        }
      }
    } catch (JsonProcessingException ignored) {
      // 瞬态片段无法编码时丢弃，不影响持久化事实。
    }
  }

  public void close(String runId) {
    var list = subscribers.remove(runId);
    if (list == null) return;
    for (var subscription : list) {
      subscription.heartbeat.cancel(false);
      try {
        subscription.emitter.complete();
      } catch (RuntimeException ignored) {
        // 已关闭的连接无需再次处理。
      }
    }
  }

  private void sendEvent(SseEmitter emitter, RunEvent event) {
    try {
      emitter.send(
          SseEmitter.event()
              .id(Long.toString(event.runSeq()))
              .name("RUN_EVENT")
              .data(json.writeValueAsString(event), MediaType.APPLICATION_JSON));
    } catch (IOException | IllegalStateException dead) {
      // 连接断开或序列化失败：由 onError/onCompletion 清理。
    }
  }

  private void remove(String runId, Subscription subscription) {
    subscription.heartbeat.cancel(false);
    var list = subscribers.get(runId);
    if (list == null) return;
    list.remove(subscription);
    if (list.isEmpty()) subscribers.remove(runId, list);
  }

  /**
   * 瞬态帧的线上结构。
   *
   * @param kind 帧类型，与 SSE event 名一致，便于旧客户端只按事件名分派。
   * @param text 正文片段或工具名。
   * @param nodeId 产生该片段的节点；未知时为 null。
   * @param detail 结构化附加信息，没有时为空对象。
   * @param occurredAt ISO-8601 服务端时间。
   */
  public record TransientPayload(
      String kind, String text, String nodeId, java.util.Map<String, Object> detail, String occurredAt) {}

  private static final class Subscription {
    private final SseEmitter emitter;
    private ScheduledFuture<?> heartbeat;

    private Subscription(SseEmitter emitter) {
      this.emitter = emitter;
    }
  }
}
