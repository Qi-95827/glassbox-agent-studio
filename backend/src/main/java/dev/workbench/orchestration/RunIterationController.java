package dev.workbench.orchestration;

import dev.workbench.ownership.OwnershipService;
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
 * 同任务多轮调改入口：发起下一轮迭代，以及按任务读取全部轮次摘要。
 *
 * <p>两条接口都先走归属校验：跨账号的运行与任务一律 404，绝不回退成「找不到就查全局」。
 */
@RestController
@RequestMapping("/api")
public class RunIterationController {
  private final RunIterationService iterations;
  private final RunRoundsService rounds;
  private final OwnershipService ownership;

  public RunIterationController(
      RunIterationService iterations, RunRoundsService rounds, OwnershipService ownership) {
    this.iterations = iterations;
    this.rounds = rounds;
    this.ownership = ownership;
  }

  /** 在同一个 taskId 下开启下一轮运行；运行中或已有活跃轮次时返回 400 与可操作原因。 */
  @PostMapping("/runs/{id}/iterate")
  RunIterationService.Iterated iterate(
      @PathVariable String id, @RequestBody(required = false) RunIterationService.Request request) {
    ownership.requireRun(id);
    return iterations.iterate(id, request);
  }

  /** 该任务下全部轮次的只读摘要，按轮次升序；用于顶部切换条与前后对照。 */
  @GetMapping("/tasks/{taskId}/runs")
  List<RunRoundsService.Round> rounds(@PathVariable String taskId) {
    ownership.requireTask(taskId);
    return rounds.rounds(taskId);
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
