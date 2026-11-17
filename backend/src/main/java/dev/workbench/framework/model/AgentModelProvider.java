package dev.workbench.framework.model;

import dev.workbench.domain.ModelMode;
import dev.workbench.framework.agent.execution.AgentExecutionRequest;
import io.agentscope.core.model.Model;

/** 可信模型适配器；返回的模型仍须由项目运行控制包装。 */
public interface AgentModelProvider {
  ModelMode mode();

  Model create(AgentExecutionRequest request);
}
