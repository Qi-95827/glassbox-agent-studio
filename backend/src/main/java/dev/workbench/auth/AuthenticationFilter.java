package dev.workbench.auth;

import dev.workbench.persistence.entity.UserEntity;
import dev.workbench.persistence.repository.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 初始化后的 API 登录校验。
 *
 * <p>旧单机数据库尚未初始化时保留本地模式，管理员完成初始化后才强制会话；登录与初始化入口始终 公开，其余 API 由服务端会话决定身份。已认证请求同时写入 Spring Security
 * 上下文。全局运维端点暂限管理员访问。
 */
public class AuthenticationFilter extends OncePerRequestFilter {
  private static final List<String> PUBLIC =
      List.of("/api/auth/status", "/api/auth/initialize", "/api/auth/login", "/api/auth/csrf");
  private static final List<String> ADMIN_ONLY =
      List.of(
          "/api/audit",
          "/api/monitoring",
          "/api/system",
          "/api/evaluations",
          "/api/cost/",
          "/api/admin/",
          "/api/external-actions",
          // 全平台唯一的向量化端点配置：普通用户改写它即可外带服务端密钥或盗用他人 BYOK 连接。
          "/api/rag-embedding",
          // 可管理评测数据集是平台级共享资产：普通用户改写它即可向他人评测注入样本。
          "/api/evaluation-datasets");

  private final AuthService auth;
  private final UserRepository users;

  public AuthenticationFilter(AuthService auth, UserRepository users) {
    this.auth = auth;
    this.users = users;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String path = request.getRequestURI();
    // 运维端点同样需要真实身份（角色由 SecurityConfiguration 校验），否则管理员会话永远落到匿名分支。
    boolean secured = path.startsWith("/api/") || path.startsWith("/actuator/");
    if (!secured || PUBLIC.contains(path) || !auth.initialized()) {
      chain.doFilter(request, response);
      return;
    }
    var session = request.getSession(false);
    Object id = session == null ? null : session.getAttribute(AuthService.SESSION_USER);
    var user =
        id instanceof String text
            ? users.findById(text).filter(item -> "ACTIVE".equals(item.getStatus()))
            : java.util.Optional.<UserEntity>empty();
    if (user.isEmpty()) {
      deny(response, 401, "请先登录。");
      return;
    }
    var authentication =
        new UsernamePasswordAuthenticationToken(
            user.get().getUsername(),
            null,
            List.of(new SimpleGrantedAuthority("ROLE_" + user.get().getRole())));
    var context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(authentication);
    SecurityContextHolder.setContext(context);
    if (ADMIN_ONLY.stream().anyMatch(path::startsWith) && !"ADMIN".equals(user.get().getRole())) {
      deny(response, 403, "该操作只允许管理员执行。");
      return;
    }
    chain.doFilter(request, response);
  }

  private static void deny(HttpServletResponse response, int status, String message)
      throws IOException {
    response.setStatus(status);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response.setContentType("application/json;charset=UTF-8");
    response.getWriter().write("{\"message\":\"" + message + "\"}");
  }
}
