package dev.workbench.auth;

import dev.workbench.framework.i18n.LocalizedMessages;
import dev.workbench.persistence.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * Spring Security 过滤器链与会话桥接。
 *
 * <p>本地账号仍由 AuthService 与会话解析，AuthenticationFilter 把已认证用户写入 Spring Security 上下文；CSRF 使用 Spring
 * Security 的 Cookie token。初始化前和未登录请求不强制 token，以便迁移与 登录入口；初始化后的状态修改请求必须携带 X-XSRF-TOKEN。
 */
@Configuration
@EnableWebSecurity
public class SecurityConfiguration {
  private static final Set<String> PUBLIC =
      Set.of("/api/auth/status", "/api/auth/initialize", "/api/auth/login", "/api/auth/csrf");

  @Bean
  SecurityFilterChain securityFilterChain(HttpSecurity http, AuthService auth, UserRepository users)
      throws Exception {
    var authentication = new AuthenticationFilter(auth, users);
    http.csrf(
            csrf ->
                csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                    .requireCsrfProtectionMatcher(new SessionCsrfMatcher(auth)))
        .authorizeHttpRequests(
            requests ->
                requests
                    // 运维端点会暴露全库聚合指标与健康细节，必须收口到管理员。
                    .requestMatchers("/actuator/**")
                    .hasRole("ADMIN")
                    .anyRequest()
                    .permitAll())
        .sessionManagement(
            sessions -> sessions.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
        .requestCache(cache -> cache.disable())
        .formLogin(form -> form.disable())
        .httpBasic(basic -> basic.disable())
        .logout(logout -> logout.disable())
        .addFilterBefore(
            authentication,
            org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
                .class)
        .exceptionHandling(
            errors ->
                errors
                    .authenticationEntryPoint(
                        (request, response, failure) ->
                            writeJson(
                                response,
                                401,
                                "AUTH_REQUIRED",
                                LocalizedMessages.get(request.getLocale(), "security.authRequired")))
                    .accessDeniedHandler(
                        (request, response, failure) -> {
                          // CSRF 校验失败与普通权限拒绝必须区分：前端只对前者刷新 token 重试。
                          boolean csrf = failure instanceof CsrfException;
                          writeJson(
                              response,
                              403,
                              csrf ? "CSRF_INVALID" : "ACCESS_DENIED",
                              LocalizedMessages.get(
                                  request.getLocale(),
                                  csrf ? "security.csrfInvalid" : "security.accessDenied"));
                        }));
    return http.build();
  }

  /**
   * 写出带稳定错误码的 JSON 错误体。
   *
   * <p>错误码与界面语言无关，前端据此做结构化分支；`message` 只作为人类可读兜底。
   * 两份文案都来自受控资源包，不含请求参数，因此无需再做 JSON 转义。
   */
  private static void writeJson(
      jakarta.servlet.http.HttpServletResponse response, int status, String errorCode, String message)
      throws java.io.IOException {
    response.setStatus(status);
    response.setCharacterEncoding(java.nio.charset.StandardCharsets.UTF_8.name());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response
        .getWriter()
        .write("{\"errorCode\":\"" + errorCode + "\",\"message\":\"" + message + "\"}");
  }

  /** 仅对初始化后的已登录状态修改请求要求 CSRF token；登录与初始化入口保持可用。 */
  static final class SessionCsrfMatcher implements RequestMatcher {
    private final AuthService auth;

    SessionCsrfMatcher(AuthService auth) {
      this.auth = auth;
    }

    @Override
    public boolean matches(HttpServletRequest request) {
      String method = request.getMethod();
      if ("GET".equals(method)
          || "HEAD".equals(method)
          || "OPTIONS".equals(method)
          || "TRACE".equals(method)) return false;
      if (!auth.initialized() || PUBLIC.contains(request.getRequestURI())) return false;
      var session = request.getSession(false);
      return session != null && session.getAttribute(AuthService.SESSION_USER) != null;
    }
  }

  /** 暴露当前 token 给同源页面；token 同时由 Spring Security 写入 XSRF-TOKEN Cookie。 */
  public static String token(HttpServletRequest request) {
    Object value = request.getAttribute(CsrfToken.class.getName());
    return value instanceof CsrfToken token ? token.getToken() : null;
  }
}
