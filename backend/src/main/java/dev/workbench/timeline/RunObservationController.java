package dev.workbench.timeline;

import dev.workbench.ownership.OwnershipService;
import java.util.Map;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运行历史的只读入口：时间轴、检查点冻结状态与两次运行的结构化对照。
 *
 * <p>三个接口都先按归属校验运行，只读不写：它们不会触发恢复、分叉或任何工具调用。
 */
@RestController
@RequestMapping("/api/runs/{id}")
public class RunObservationController {
  private final RunObservationService observations;
  private final OwnershipService ownership;

  public RunObservationController(RunObservationService observations, OwnershipService ownership) {
    this.observations = observations;
    this.ownership = ownership;
  }

  @GetMapping("/timeline")
  Object timeline(@PathVariable String id) {
    ownership.requireRun(id);
    return observations.timeline(id);
  }

  @GetMapping("/checkpoints")
  Object checkpoints(@PathVariable String id) {
    ownership.requireRun(id);
    return observations.checkpoints(id);
  }

  @GetMapping("/diff/{otherId}")
  Object diff(@PathVariable String id, @PathVariable String otherId) {
    ownership.requireRun(id);
    ownership.requireRun(otherId);
    return observations.diff(id, otherId);
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
