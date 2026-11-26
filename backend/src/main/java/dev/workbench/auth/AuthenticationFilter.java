package dev.workbench.auth;

import dev.workbench.framework.i18n.LocalizedMessages;
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
      deny(request, response, 401, "AUTH_REQUIRED", "security.authRequired", "请先登录。");
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
      deny(
          request,
          response,
          403,
          "ACCESS_DENIED",
          "security.adminOnly",
          "该操作只允许管理员执行。");
      return;
    }
    chain.doFilter(request, response);
  }

  /**
   * 写出带稳定错误码的 JSON 拒绝响应。
   *
   * <p>错误码与界面语言无关，前端据此决定「刷新 CSRF token 重试」还是直接提示无权限；
   * 译文来自受控资源包，`fallback` 只在资源缺失时兜底，两者都不含请求参数。
   */
  private static void deny(
      HttpServletRequest request,
      HttpServletResponse response,
      int status,
      String errorCode,
      String messageKey,
      String fallback)
      throws IOException {
    response.setStatus(status);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response.setContentType("application/json;charset=UTF-8");
    String localized = LocalizedMessages.get(request.getLocale(), messageKey);
    String message = messageKey.equals(localized) ? fallback : localized;
    response.getWriter().write("{\"errorCode\":\"" + errorCode + "\",\"message\":\"" + message + "\"}");
  }
}
