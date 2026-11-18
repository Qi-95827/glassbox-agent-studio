package dev.workbench.framework.event;

import dev.workbench.domain.EventType;
import dev.workbench.task.RunRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 以排他游标读取指定运行的事件，避免重连后重复处理。
 *
 * <p>游标是每个运行的提交序号（{@code run_seq}），不是全局自增 ID：跨运行并发时全局 ID 会跳号，
 * 同运行晚提交的事件也可能落在旧 ID 游标之前。调用方只应回传 {@code nextCursor}，不得假设数字连续。
 */
@Service
public class EventQueryService {
  private final JdbcTemplate db;
  private final RunRepository runs;

  public EventQueryService(JdbcTemplate db, RunRepository runs) {
    this.db = db;
    this.runs = runs;
  }

  public EventPage page(String runId, long after, int limit) {
    if (after < 0 || limit < 1 || limit > 200) throw new IllegalArgumentException("事件游标或分页数量无效。");
    runs.runSnapshot(runId);
    var events =
        db.query(
            "SELECT id,run_seq,run_id,event_type,message,created_at,plan_version_id,node_id,"
                + "attempt_id,agent_id,check_id,model_call_id,tool_call_id,reason_code FROM events"
                + " WHERE run_id=? AND run_seq>? AND run_seq IS NOT NULL ORDER BY run_seq LIMIT ?",
            (r, i) ->
                new RunEvent(
                    r.getLong("id"),
                    r.getLong("run_seq"),
                    UUID.fromString(r.getString("run_id")),
                    EventType.valueOf(r.getString("event_type")),
                    r.getString("message"),
                    Instant.parse(r.getString("created_at")),
                    nullableUuid(r.getString("plan_version_id")),
                    nullableUuid(r.getString("node_id")),
                    nullableUuid(r.getString("attempt_id")),
                    nullableUuid(r.getString("agent_id")),
                    nullableUuid(r.getString("check_id")),
                    nullableUuid(r.getString("model_call_id")),
                    nullableUuid(r.getString("tool_call_id")),
                    r.getString("reason_code")),
            runId,
            after,
            limit);
    return new EventPage(events, events.isEmpty() ? after : events.getLast().runSeq());
  }

  private static UUID nullableUuid(String value) {
    return value == null || value.isBlank() ? null : UUID.fromString(value);
  }
}
