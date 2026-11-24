package dev.workbench.app;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 本地版阻止跨站网页直接调用 API 与运维端点；这不是用户认证或多租户隔离。
 *
 * <p>必须排在过滤器链最前（早于 Spring Security）：否则跨站请求可能先被认证链处理，或绕过本地来源 判定直接访问 {@code /actuator/**}。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class LocalOriginFilter extends OncePerRequestFilter {
  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String path = request.getRequestURI();
    if (!path.startsWith("/api/") && !path.startsWith("/actuator/")) {
      chain.doFilter(request, response);
      return;
    }
    String host = request.getServerName();
    if (!("127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host))) {
      response.sendError(403);
      return;
    }
    String origin = request.getHeader("Origin");
    if (origin != null) {
      try {
        URI uri = URI.create(origin);
        if (!"http".equals(uri.getScheme())
            || !host.equalsIgnoreCase(uri.getHost())
            || uri.getPort() != request.getServerPort()) {
          response.sendError(403);
          return;
        }
      } catch (IllegalArgumentException e) {
        response.sendError(403);
        return;
      }
    }
    chain.doFilter(request, response);
  }
}
