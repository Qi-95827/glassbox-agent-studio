package dev.workbench.task;

import dev.workbench.task.DeliveryAccessDeniedException;
import dev.workbench.task.RunDeliveryService;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 运行级交付说明只读投影；文件正文仍走工作区读取接口并核对当前摘要。 */
@RestController
@RequestMapping("/api/runs/{id}/delivery")
public class RunDeliveryController {
  private final RunDeliveryService deliveries;

  public RunDeliveryController(RunDeliveryService deliveries) {
    this.deliveries = deliveries;
  }

  @GetMapping
  Object delivery(@PathVariable String id) {
    return deliveries.delivery(id);
  }

  /** 交付正文：路径必须属于该运行的成功写入回执，且当前摘要一致才返回正文。 */
  @GetMapping("/files")
  Object file(@PathVariable String id, @RequestParam String path) {
    return deliveries.file(id, path);
  }

  @ExceptionHandler(EmptyResultDataAccessException.class)
  @ResponseStatus(HttpStatus.NOT_FOUND)
  Object notFound() {
    return java.util.Map.of("message", "记录不存在。");
  }

  @ExceptionHandler(DeliveryAccessDeniedException.class)
  @ResponseStatus(HttpStatus.FORBIDDEN)
  Object denied(DeliveryAccessDeniedException e) {
    return java.util.Map.of("message", e.getMessage());
  }

  @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  Object invalid(RuntimeException e) {
    return java.util.Map.of("message", e.getMessage() == null ? "请求无效。" : e.getMessage());
  }
}
