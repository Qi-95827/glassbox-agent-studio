package dev.workbench.auth;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 本地账号入口：初始化、登录、退出和管理员添加用户。 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {
  private final AuthService auth;

  public AuthController(AuthService auth) {
    this.auth = auth;
  }

  @GetMapping("/status")
  Object status() {
    return auth.status();
  }

  @PostMapping("/initialize")
  Object initialize(@RequestBody AuthService.InitializeRequest request, HttpServletRequest http) {
    return auth.initialize(request, http);
  }

  @PostMapping("/login")
  Object login(@RequestBody AuthService.LoginRequest request, HttpServletRequest http) {
    return auth.login(request, http);
  }

  @PostMapping("/logout")
  Object logout(HttpServletRequest http) {
    auth.logout(http);
    return java.util.Map.of("loggedOut", true);
  }

  /** 同源页面获取 CSRF token；状态修改请求通过 X-XSRF-TOKEN 回传。 */
  @GetMapping("/csrf")
  Object csrf(HttpServletRequest request) {
    String token = SecurityConfiguration.token(request);
    if (token == null) throw new IllegalStateException("CSRF token 尚未初始化。");
    return java.util.Map.of("token", token, "headerName", "X-XSRF-TOKEN");
  }

  @GetMapping("/users")
  Object users() {
    return auth.listUsers();
  }

  @PostMapping("/users")
  Object addUser(@RequestBody AuthService.AddUserRequest request) {
    return auth.addUser(request);
  }

  @PostMapping("/claim-legacy")
  Object claimLegacy() {
    return auth.claimLegacyHistory();
  }

  @ExceptionHandler(AuthService.AuthRequiredException.class)
  @ResponseStatus(HttpStatus.UNAUTHORIZED)
  Object unauthorized(AuthService.AuthRequiredException e) {
    return java.util.Map.of("message", e.getMessage());
  }

  @ExceptionHandler(AuthService.AccessDeniedException.class)
  @ResponseStatus(HttpStatus.FORBIDDEN)
  Object forbidden(AuthService.AccessDeniedException e) {
    return java.util.Map.of("message", e.getMessage());
  }

  @ExceptionHandler(AuthService.InvalidCredentialsException.class)
  @ResponseStatus(HttpStatus.UNAUTHORIZED)
  Object invalidCredentials(AuthService.InvalidCredentialsException e) {
    return java.util.Map.of("message", e.getMessage());
  }

  @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  Object invalid(RuntimeException e) {
    return java.util.Map.of("message", e.getMessage() == null ? "请求无效。" : e.getMessage());
  }
}
