package dev.workbench.orchestration.events;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 单运行事件增量查询，默认每页最多一百条。 */
@RestController
public class EventController {
  private final EventQueryService events;

  public EventController(EventQueryService events) {
    this.events = events;
  }

  @GetMapping("/api/runs/{id}/events")
  public EventPage page(
      @PathVariable String id,
      @RequestParam(defaultValue = "0") long after,
      @RequestParam(defaultValue = "100") int limit) {
    return events.page(id, after, limit);
  }
}
