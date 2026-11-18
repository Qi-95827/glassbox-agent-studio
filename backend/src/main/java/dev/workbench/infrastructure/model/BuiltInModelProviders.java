package dev.workbench.infrastructure.model;

import dev.workbench.domain.AgentRole;
import dev.workbench.domain.ModelMode;
import dev.workbench.framework.agent.definition.AgentDefinitionRegistry;
import dev.workbench.framework.agent.execution.AgentExecutionRequest;
import dev.workbench.framework.context.AgentInput;
import dev.workbench.framework.model.AgentModelProvider;
import io.agentscope.core.model.Model;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 当前内置的模型适配；真实模型只在该模式被选中时构建。 */
@Configuration
class BuiltInModelProviders {
  @Bean
  AgentModelProvider mockAgentModelProvider(AgentDefinitionRegistry definitions) {
    return new AgentModelProvider() {
      public ModelMode mode() {
        return ModelMode.MOCK;
      }

      public Model create(AgentExecutionRequest request) {
        boolean workflowInput =
            request.context().sources().stream()
                .anyMatch(source -> source.kind() == AgentInput.Kind.WORKFLOW_NODE);
        boolean textOnly =
            (request.role() == AgentRole.ANALYST || workflowInput)
                && request.definitionRef() != null
                && definitions.resolve(request.definitionRef()).tools().isEmpty();
        return new DemoModel(
            request.role(),
            request.workspaceId() != null,
            request.scenario(),
            request.fileScopes(),
            textOnly,
            workflowInput);
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

      @Override
      public boolean isRateLimitRejection(Throwable error) {
        return DeepSeekModelFactory.isRateLimitRejection(error);
      }
    };
  }
}
