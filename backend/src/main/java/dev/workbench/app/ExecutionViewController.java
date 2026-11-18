package dev.workbench.app;

import dev.workbench.framework.workflow.view.ExecutionViewService;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 任务执行流程图只读投影；写操作继续使用既有运行控制接口。 */
@RestController
@RequestMapping("/api/runs/{id}/execution-view")
public class ExecutionViewController {
  private final ExecutionViewService views;

  public ExecutionViewController(ExecutionViewService views) {
    this.views = views;
  }

  @GetMapping
  Object view(@PathVariable String id) {
    return views.view(id);
  }

  @GetMapping("/nodes/{nodeId}")
  Object node(@PathVariable String id, @PathVariable String nodeId) {
    return views.detail(id, nodeId);
  }

  @ExceptionHandler(EmptyResultDataAccessException.class)
  @ResponseStatus(HttpStatus.NOT_FOUND)
  Object notFound() {
    return java.util.Map.of("message", "记录不存在。");
  }

  @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  Object invalid(RuntimeException e) {
    return java.util.Map.of("message", e.getMessage() == null ? "请求无效。" : e.getMessage());
  }
}
