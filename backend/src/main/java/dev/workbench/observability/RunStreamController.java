package dev.workbench.observability;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 单运行 SSE 实时事件通道；归属校验与增量补发均在服务端完成。 */
@RestController
public class RunStreamController {
  private final RunStreamBroker streams;
  private final dev.workbench.ownership.OwnershipService ownership;

  public RunStreamController(
      RunStreamBroker streams, dev.workbench.ownership.OwnershipService ownership) {
    this.streams = streams;
    this.ownership = ownership;
  }

  @GetMapping(value = "/api/runs/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter stream(
      @PathVariable String id,
      @RequestHeader(value = "Last-Event-ID", required = false) Long lastEventId) {
    ownership.requireRun(id);
    return streams.subscribe(id, lastEventId);
  }
}
