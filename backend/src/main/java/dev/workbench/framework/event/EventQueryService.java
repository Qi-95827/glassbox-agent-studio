package dev.workbench.framework.event;

import dev.workbench.domain.EventType;
import dev.workbench.persistence.entity.EventEntity;
import dev.workbench.persistence.repository.EventRepository;
import dev.workbench.task.RunRecordService;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/**
 * 以排他游标读取指定运行的事件，避免重连后重复处理。
 *
 * <p>游标是每个运行的提交序号（{@code run_seq}），不是全局自增 ID：跨运行并发时全局 ID 会跳号，
 * 同运行晚提交的事件也可能落在旧 ID 游标之前。调用方只应回传 {@code nextCursor}，不得假设数字连续。
 */
@Service
public class EventQueryService {
  private final EventRepository events;
  private final RunRecordService runs;

  public EventQueryService(EventRepository events, RunRecordService runs) {
    this.events = events;
    this.runs = runs;
  }

  public EventPage page(String runId, long after, int limit) {
    if (after < 0 || limit < 1 || limit > 200) throw new IllegalArgumentException("事件游标或分页数量无效。");
    runs.runSnapshot(runId);
    var page =
        events
            .findByRunIdAndRunSeqGreaterThanOrderByRunSeqAsc(
                runId, after, PageRequest.of(0, limit))
            .stream()
            .map(EventQueryService::toEvent)
            .toList();
    return new EventPage(page, page.isEmpty() ? after : page.getLast().runSeq());
  }

  private static RunEvent toEvent(EventEntity event) {
    return new RunEvent(
        event.getId(),
        event.getRunSeq(),
        UUID.fromString(event.getRunId()),
        EventType.valueOf(event.getEventType()),
        event.getMessage(),
        Instant.parse(event.getCreatedAt()),
        nullableUuid(event.getPlanVersionId()),
        nullableUuid(event.getNodeId()),
        nullableUuid(event.getAttemptId()),
        nullableUuid(event.getAgentId()),
        nullableUuid(event.getCheckId()),
        nullableUuid(event.getModelCallId()),
        nullableUuid(event.getToolCallId()),
        event.getReasonCode());
  }

  private static UUID nullableUuid(String value) {
    return value == null || value.isBlank() ? null : UUID.fromString(value);
  }
}