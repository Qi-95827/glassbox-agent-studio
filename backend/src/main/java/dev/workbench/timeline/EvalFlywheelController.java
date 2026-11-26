package dev.workbench.timeline;

import dev.workbench.ownership.OwnershipService;
import java.util.Map;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 评测飞轮入口：把真实运行脱敏沉淀为 Eval Lab 自定义数据集用例。
 *
 * <p>预览只读不写；落库复用既有自定义数据集校验与评分口径，不新增评测规则。
 */
@RestController
@RequestMapping("/api/runs/{id}")
public class EvalFlywheelController {
  private final EvalFlywheelService flywheel;
  private final OwnershipService ownership;

  public EvalFlywheelController(EvalFlywheelService flywheel, OwnershipService ownership) {
    this.flywheel = flywheel;
    this.ownership = ownership;
  }

  @GetMapping("/eval-case-preview")
  Object preview(@PathVariable String id) {
    ownership.requireRun(id);
    return flywheel.preview(id);
  }

  @PostMapping("/eval-cases")
  Object export(@PathVariable String id) {
    ownership.requireRun(id);
    return flywheel.export(id);
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
