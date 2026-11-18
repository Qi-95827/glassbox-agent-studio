package dev.workbench.framework.model;

import dev.workbench.domain.ModelMode;
import dev.workbench.framework.agent.execution.AgentExecutionRequest;
import io.agentscope.core.model.Model;

/** 可信模型适配器；返回的模型仍须由项目运行控制包装。 */
public interface AgentModelProvider {
  ModelMode mode();

  /** 已配置的适配器行为发生不兼容变化时更新版本。 */
  default int configurationVersion() {
    return 1;
  }

  Model create(AgentExecutionRequest request);

  /** 仅在能从可信适配器错误确认服务拒绝请求时返回真。 */
  default boolean isRateLimitRejection(Throwable error) {
    return false;
  }
}
