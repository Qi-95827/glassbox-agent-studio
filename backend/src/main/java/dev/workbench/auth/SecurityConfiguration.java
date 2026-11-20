package dev.workbench.auth;

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
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * Spring Security 过滤器链与会话桥接。
 *
 * <p>本地账号仍由 AuthService 与会话解析，AuthenticationFilter 把已认证用户写入 Spring Security
 * 上下文；CSRF 使用 Spring Security 的 Cookie token。初始化前和未登录请求不强制 token，以便迁移与
 * 登录入口；初始化后的状态修改请求必须携带 X-XSRF-TOKEN。
 */
@Configuration
@EnableWebSecurity
public class SecurityConfiguration {
  private static final Set<String> PUBLIC =
      Set.of(
          "/api/auth/status",
          "/api/auth/initialize",
          "/api/auth/login",
          "/api/auth/csrf");

  @Bean
  SecurityFilterChain securityFilterChain(
      HttpSecurity http, AuthService auth, UserRepository users) throws Exception {
    var authentication = new AuthenticationFilter(auth, users);
    http.csrf(
            csrf ->
                csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                    .requireCsrfProtectionMatcher(new SessionCsrfMatcher(auth)))
        .authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
        .sessionManagement(
            sessions -> sessions.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
        .requestCache(cache -> cache.disable())
        .formLogin(form -> form.disable())
        .httpBasic(basic -> basic.disable())
        .logout(logout -> logout.disable())
        .addFilterBefore(authentication, org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter.class)
        .exceptionHandling(
            errors ->
                errors
                    .authenticationEntryPoint(
                        (request, response, failure) -> {
                          response.setStatus(401);
                          response.setCharacterEncoding(java.nio.charset.StandardCharsets.UTF_8.name());
                          response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                          response.getWriter().write("{\"message\":\"请先登录。\"}");
                        })
                    .accessDeniedHandler(
                        (request, response, failure) -> {
                          response.setStatus(403);
                          response.setCharacterEncoding(java.nio.charset.StandardCharsets.UTF_8.name());
                          response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                          response.getWriter().write("{\"message\":\"请求校验失败，请刷新页面后重试。\"}");
                        }));
    return http.build();
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
