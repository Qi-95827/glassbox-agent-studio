package dev.workbench.infrastructure.model;

import dev.workbench.domain.ModelMode;
import dev.workbench.framework.agent.execution.AgentExecutionRequest;
import dev.workbench.framework.model.AgentModelProvider;
import io.agentscope.core.model.Model;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 当前内置的模型适配；真实模型只在该模式被选中时构建。 */
@Configuration
class BuiltInModelProviders {
  @Bean
  AgentModelProvider mockAgentModelProvider() {
    return new AgentModelProvider() {
      public ModelMode mode() {
        return ModelMode.MOCK;
      }

      public Model create(AgentExecutionRequest request) {
        return new DemoModel(
            request.role(),
            request.workspaceId() != null,
            request.scenario(),
            request.fileScopes());
      }
    };
  }

  @Bean
  AgentModelProvider deepSeekAgentModelProvider() {
    return new AgentModelProvider() {
      public ModelMode mode() {
        return ModelMode.DEEPSEEK;
      }

      public Model create(AgentExecutionRequest request) {
        return DeepSeekModelFactory.deepSeekModel();
      }
    };
  }
}
