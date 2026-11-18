package dev.workbench.agent;

import dev.workbench.domain.ToolKind;
import dev.workbench.framework.tool.AgentToolProvider;
import dev.workbench.framework.tool.SimulatedTool;
import dev.workbench.framework.tool.ToolContracts;
import dev.workbench.framework.tool.ToolSpecification;
import dev.workbench.workspace.AuthorizedCodeSearch;
import dev.workbench.workspace.AuthorizedFileSet;
import dev.workbench.workspace.WorkspaceService;
import java.util.function.Function;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 注入现有工具业务服务，提供由框架装配的工具声明。 */
@Configuration
class BuiltInToolProviders {
  private static AgentToolProvider provider(
      ToolKind kind, Function<AgentToolProvider.Scope, ToolSpecification<?>> factory) {
    return new AgentToolProvider() {
      public ToolKind kind() {
        return kind;
      }

      public int contractVersion(int count) {
        return ToolContracts.versionFor(kind, count);
      }

      public ToolSpecification<?> create(Scope scope) {
        return factory.apply(scope);
      }
    };
  }

  @Bean
  AgentToolProvider simulatedToolProvider(SimulatedTool simulatedTool) {
    return provider(
        ToolKind.SIMULATED_INSPECTION,
        scope -> AgentTool.specification(simulatedTool, scope.scenario()));
  }

  @Bean
  AgentToolProvider workspaceReadProvider(WorkspaceService workspaces) {
    return provider(
        ToolKind.WORKSPACE_READ,
        scope -> {
          var files = new AuthorizedFileSet(scope.authorizedPaths());
          return files.paths().size() == 1
              ? WorkspaceReadTool.specification(
                  workspaces, scope.workspaceId(), files.paths().getFirst())
              : WorkspaceScopedReadTool.specification(workspaces, scope.workspaceId(), files);
        });
  }

  @Bean
  AgentToolProvider workspaceWriteProvider(WorkspaceService workspaces) {
    return provider(
        ToolKind.WORKSPACE_WRITE,
        scope -> {
          var files = new AuthorizedFileSet(scope.authorizedPaths());
          return files.paths().size() == 1
              ? WorkspaceWriteTool.specification(
                  workspaces, scope.workspaceId(), files.paths().getFirst())
              : WorkspaceScopedWriteTool.specification(workspaces, scope.workspaceId(), files);
        });
  }

  @Bean
  AgentToolProvider workspaceSearchProvider(WorkspaceService workspaces) {
    return provider(
        ToolKind.WORKSPACE_SEARCH,
        scope ->
            WorkspaceSearchTool.specification(
                new AuthorizedCodeSearch(workspaces),
                scope.workspaceId(),
                new AuthorizedFileSet(scope.authorizedPaths())));
  }
}
