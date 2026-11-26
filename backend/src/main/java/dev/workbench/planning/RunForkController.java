package dev.workbench.planning;

import dev.workbench.domain.EventType;
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
 * 分支运行入口：从历史运行分叉、查询某条运行的所有分支。
 *
 * <p>两条接口都先按归属校验源运行；分叉本身只复用计划与授权，不改写源运行的任何记录。
 */
@RestController
@RequestMapping("/api/runs/{id}/forks")
public class RunForkController {
  private final RunForkService forks;
  private final dev.workbench.ownership.OwnershipService ownership;

  public RunForkController(
      RunForkService forks, dev.workbench.ownership.OwnershipService ownership) {
    this.forks = forks;
    this.ownership = ownership;
  }

  @PostMapping
  RunForkService.Forked fork(
      @PathVariable String id, @RequestBody(required = false) RunForkService.Request request) {
    ownership.requireRun(id);
    return forks.fork(id, request);
  }

  @GetMapping
  RunForkService.ForkList list(@PathVariable String id) {
    ownership.requireRun(id);
    return forks.forks(id);
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
