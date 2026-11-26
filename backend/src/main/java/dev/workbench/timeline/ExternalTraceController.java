package dev.workbench.timeline;

import java.util.List;
import java.util.Map;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 外部 Agent 框架接入入口。
 *
 * <p>归属由服务端从当前登录账号推导，客户端不能指定：跨账号读写一律按「记录不存在」处理。
 */
@RestController
@RequestMapping("/api/external-traces")
public class ExternalTraceController {
  private final ExternalTraceService traces;

  public ExternalTraceController(ExternalTraceService traces) {
    this.traces = traces;
  }

  @GetMapping("/runs")
  Object list() {
    return traces.list();
  }

  @PostMapping("/runs")
  Object create(@RequestBody(required = false) ExternalTraceService.CreateRequest request) {
    return traces.create(request);
  }

  @GetMapping("/runs/{id}")
  Object get(@PathVariable String id) {
    return traces.get(id);
  }

  @PostMapping("/runs/{id}/spans")
  Object append(
      @PathVariable String id,
      @RequestBody(required = false) List<ExternalTraceService.SpanInput> spans) {
    return traces.append(id, spans);
  }

  @ExceptionHandler(EmptyResultDataAccessException.class)
  @ResponseStatus(HttpStatus.NOT_FOUND)
  Object notFound() {
    return Map.of("message", "记录不存在。");
  }

  @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  Object invalid(RuntimeException e) {
    return Map.of("message", e.getMessage() == null ? "请求无效。" : e.getMessage());
  }
}
