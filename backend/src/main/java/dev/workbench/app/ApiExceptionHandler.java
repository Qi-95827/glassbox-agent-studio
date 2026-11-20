package dev.workbench.app;

import dev.workbench.planning.PlanCandidateConflictException;
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

  /** 同一请求标识用于不同内容时要求客户端换标识或读取原记录。 */
  @ExceptionHandler(PlanCandidateConflictException.class)
  @ResponseStatus(HttpStatus.CONFLICT)
  public Map<String, String> conflict(PlanCandidateConflictException error) {
    return Map.of("message", error.getMessage());
  }

  /** 越权访问统一返回 403，不区分目标不存在与无权限。 */
  @ExceptionHandler(dev.workbench.auth.AuthService.AccessDeniedException.class)
  @ResponseStatus(HttpStatus.FORBIDDEN)
  public Map<String, String> forbidden(dev.workbench.auth.AuthService.AccessDeniedException error) {
    return Map.of("message", error.getMessage());
  }

  @ExceptionHandler(dev.workbench.auth.AuthService.AuthRequiredException.class)
  @ResponseStatus(HttpStatus.UNAUTHORIZED)
  public Map<String, String> unauthorized(
      dev.workbench.auth.AuthService.AuthRequiredException error) {
    return Map.of("message", error.getMessage());
  }

  @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  public Map<String, String> invalid(RuntimeException error) {
    return Map.of("message", error.getMessage() == null ? "操作失败。" : error.getMessage());
  }
}
