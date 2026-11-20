package dev.workbench.planning;

/**
 * 从服务端环境解析当前执行归属。
 *
 * <p>以后接入登录和项目权限时替换实现即可；调用方不得从请求体拼装归属。
 */
public interface ExecutionScopeResolver {
  ExecutionScope current();
}
