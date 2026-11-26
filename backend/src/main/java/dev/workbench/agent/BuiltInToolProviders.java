package dev.workbench.agent;

import dev.workbench.control.ContextArtifactService;
import dev.workbench.domain.ToolKind;
import dev.workbench.framework.tool.AgentToolProvider;
import dev.workbench.framework.tool.SimulatedTool;
import dev.workbench.framework.tool.ToolContracts;
import dev.workbench.framework.tool.ToolSpecification;
import dev.workbench.memory.knowledge.KnowledgeRagService;
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
        scope -> AgentTool.specification(simulatedTool, scope.scenario(), scope.locale()));
  }

  @Bean
  AgentToolProvider workspaceReadProvider(WorkspaceService workspaces) {
    return provider(
        ToolKind.WORKSPACE_READ,
        scope -> {
          var files = new AuthorizedFileSet(scope.authorizedPaths());
          return files.paths().size() == 1
              ? WorkspaceReadTool.specification(
                  workspaces, scope.workspaceId(), files.paths().getFirst(), scope.locale())
              : WorkspaceScopedReadTool.specification(
                  workspaces, scope.workspaceId(), files, scope.locale());
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
                  workspaces, scope.workspaceId(), files.paths().getFirst(), scope.locale())
              : WorkspaceScopedWriteTool.specification(
                  workspaces, scope.workspaceId(), files, scope.locale());
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
                new AuthorizedFileSet(scope.authorizedPaths()),
                scope.locale()));
  }

  /**
   * 知识库检索：只读、工作区隔离。
   *
   * <p>没有工作区时直接拒绝装配，而不是退化成一个永远返回空结果的工具——让模型以为
   * 「检索过了什么都没找到」比明确不可用更具误导性。
   */
  @Bean
  AgentToolProvider knowledgeSearchProvider(KnowledgeRagService rag) {
    return provider(
        ToolKind.KNOWLEDGE_SEARCH,
        scope -> {
          if (scope.workspaceId() == null || scope.workspaceId().isBlank())
            throw new IllegalStateException("知识库检索需要工作区范围。");
          return KnowledgeSearchTool.specification(rag, scope.workspaceId(), scope.locale());
        });
  }

  /** 上下文产物回读：只读、运行内匹配。 */
  @Bean
  AgentToolProvider contextArtifactReadProvider(ContextArtifactService artifacts) {
    return provider(
        ToolKind.CONTEXT_ARTIFACT_READ,
        scope -> {
          if (scope.runId() == null || scope.runId().isBlank())
            throw new IllegalStateException("上下文产物回读需要运行标识。");
          return ContextArtifactReadTool.specification(artifacts, scope.runId(), scope.locale());
        });
  }
}
