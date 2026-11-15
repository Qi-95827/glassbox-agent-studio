package dev.workbench.app;

import java.util.Map;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 为各模块提供一致的受控错误响应，避免向页面暴露内部堆栈。 */
@RestControllerAdvice
public class ApiExceptionHandler {
  @ExceptionHandler(EmptyResultDataAccessException.class)
  @ResponseStatus(HttpStatus.NOT_FOUND)
  public Map<String, String> notFound() {
    return Map.of("message", "记录不存在。");
  }

  @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  public Map<String, String> invalid(RuntimeException error) {
    return Map.of("message", error.getMessage() == null ? "操作失败。" : error.getMessage());
  }
}
