package dev.workbench.agent;

import dev.workbench.domain.ToolKind;
import dev.workbench.framework.tool.AgentToolContext;
import dev.workbench.framework.tool.AgentToolProvider;
import dev.workbench.framework.tool.SimulatedTool;
import dev.workbench.framework.tool.ToolContracts;
import dev.workbench.workspace.AuthorizedCodeSearch;
import dev.workbench.workspace.AuthorizedFileSet;
import dev.workbench.workspace.WorkspaceService;
import java.util.function.Function;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 将既有工具作为适配器注册；权限由角色装配计划和调用网关共同约束。 */
@Configuration
class BuiltInToolProviders {
  private static AgentToolProvider provider(
      ToolKind kind, Function<AgentToolContext, Object> factory) {
    return new AgentToolProvider() {
      public ToolKind kind() {
        return kind;
      }

      public int contractVersion(int count) {
        return ToolContracts.versionFor(kind, count);
      }

      public Object create(AgentToolContext context) {
        return factory.apply(context);
      }
    };
  }

  @Bean
  AgentToolProvider simulatedToolProvider(SimulatedTool simulatedTool) {
    return provider(
        ToolKind.SIMULATED_INSPECTION,
        c -> new AgentTool(simulatedTool, c.scenario(), c.json(), c.runtime(), c.failure()));
  }

  @Bean
  AgentToolProvider workspaceReadProvider(WorkspaceService workspaces) {
    return provider(
        ToolKind.WORKSPACE_READ,
        c -> {
          var files = new AuthorizedFileSet(c.authorizedPaths());
          return files.paths().size() == 1
              ? new WorkspaceReadTool(
                  workspaces,
                  c.json(),
                  c.workspaceId(),
                  files.paths().getFirst(),
                  c.runtime(),
                  c.failure())
              : new WorkspaceScopedReadTool(
                  workspaces, c.workspaceId(), files, c.json(), c.runtime(), c.failure());
        });
  }

  @Bean
  AgentToolProvider workspaceWriteProvider(WorkspaceService workspaces) {
    return provider(
        ToolKind.WORKSPACE_WRITE,
        c -> {
          var files = new AuthorizedFileSet(c.authorizedPaths());
          return files.paths().size() == 1
              ? new WorkspaceWriteTool(
                  workspaces,
                  c.workspaceId(),
                  files.paths().getFirst(),
                  c.json(),
                  c.runtime(),
                  c.failure())
              : new WorkspaceScopedWriteTool(
                  workspaces, c.workspaceId(), files, c.json(), c.runtime(), c.failure());
        });
  }

  @Bean
  AgentToolProvider workspaceSearchProvider(WorkspaceService workspaces) {
    return provider(
        ToolKind.WORKSPACE_SEARCH,
        c ->
            new WorkspaceSearchTool(
                new AuthorizedCodeSearch(workspaces),
                c.workspaceId(),
                new AuthorizedFileSet(c.authorizedPaths()),
                c.json(),
                c.runtime(),
                c.failure()));
  }
}
