package dev.workbench.framework.tool;

import dev.workbench.domain.ToolKind;

/** 可信工具适配器扩展点；实现必须通过统一网关执行实际调用。 */
public interface AgentToolProvider {
  ToolKind kind();

  /** 契约版本由服务端依据任务范围确定，不能由模型选择。 */
  int contractVersion(int authorizedFileCount);

  /** 返回恰好一个可供 AgentScope 注册的工具对象。 */
  Object create(AgentToolContext context);
}
