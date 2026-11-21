package dev.workbench.planning;

import dev.workbench.auth.AuthService;
import org.springframework.stereotype.Component;
import dev.workbench.domain.ExecutionScope;
import dev.workbench.domain.ExecutionScopeResolver;

/**
 * 从已认证会话解析当前执行归属。
 *
 * <p>初始化完成且已登录时使用用户归属；未初始化或没有会话时保留 LOCAL，以兼容旧单机数据和本地 验收。HTTP 层的登录过滤器负责拒绝初始化后的匿名请求，服务层仍从这里取得归属，不信任请求体。
 */
@Component
public class LocalExecutionScopeResolver implements ExecutionScopeResolver {
  private final AuthService auth;

  public LocalExecutionScopeResolver(AuthService auth) {
    this.auth = auth;
  }

  @Override
  public ExecutionScope current() {
    return auth.scope();
  }
}
