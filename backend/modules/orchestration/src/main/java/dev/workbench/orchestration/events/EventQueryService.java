package dev.workbench.orchestration.events;

import dev.workbench.domain.EventType;
import dev.workbench.task.RunRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 以排他游标读取指定运行的事件，避免重连后重复处理。 */
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
            "SELECT id,run_id,event_type,message,created_at FROM events WHERE run_id=? AND id>?"
                + " ORDER BY id LIMIT ?",
            (r, i) ->
                new RunEvent(
                    r.getLong("id"),
                    UUID.fromString(r.getString("run_id")),
                    EventType.valueOf(r.getString("event_type")),
                    r.getString("message"),
                    Instant.parse(r.getString("created_at"))),
            runId,
            after,
            limit);
    return new EventPage(events, events.isEmpty() ? after : events.getLast().id());
  }
}
