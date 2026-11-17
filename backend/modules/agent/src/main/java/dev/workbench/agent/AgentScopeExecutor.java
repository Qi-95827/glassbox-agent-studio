package dev.workbench.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.workbench.domain.AgentRole;
import dev.workbench.domain.CheckpointStage;
import dev.workbench.domain.ModelMode;
import dev.workbench.domain.Scenario;
import dev.workbench.domain.ToolKind;
import dev.workbench.tool.SimulatedTool;
import dev.workbench.tool.ToolContracts;
import dev.workbench.workspace.AuthorizedCodeSearch;
import dev.workbench.workspace.AuthorizedFileSet;
import dev.workbench.workspace.WorkspaceService;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.Model;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.ConflictPolicy;
import io.agentscope.core.tool.Toolkit;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/** 使用 AgentScope 的 ReActAgent 运行已选角色，项目控制器负责限额与记录。 */
@Component
public class AgentScopeExecutor {
  private final SimulatedTool tool;
  private final WorkspaceService workspaces;
  private final ObjectMapper json;
  private final AgentStateStore stateStore;
  private final AgentAssemblyPlanner assemblyPlanner;

  public AgentScopeExecutor(
      SimulatedTool tool,
      WorkspaceService workspaces,
      ObjectMapper json,
      AgentStateStore stateStore,
      AgentAssemblyPlanner assemblyPlanner) {
    this.tool = tool;
    this.workspaces = workspaces;
    this.json = json;
    this.stateStore = stateStore;
    this.assemblyPlanner = assemblyPlanner;
  }

  /** 创建每次运行独立的框架 Agent；工具按角色及用户授权注册。 */
  public String execute(
      AgentRole role,
      String agentId,
      String goal,
      String workspaceId,
      List<String> fileScopes,
      boolean writeEnabled,
      Scenario scenario,
      ModelMode mode,
      CheckpointStage stage,
      String previous,
      AgentRuntime runtime)
      throws InterruptedException {
    java.util.UUID.fromString(agentId);
    if (stage != CheckpointStage.BEFORE_TOOL)
      throw new IllegalArgumentException("当前仅支持从 Agent 启动前恢复。");
    if (fileScopes == null
        || (workspaceId == null && !fileScopes.isEmpty())
        || (workspaceId != null && fileScopes.isEmpty()))
      throw new IllegalArgumentException("工作区授权文件范围无效。");
    var scopedFiles = workspaceId == null ? null : new AuthorizedFileSet(fileScopes);
    var assembly = assemblyPlanner.plan(role, workspaceId != null, fileScopes.size(), writeEnabled);
    AtomicReference<RuntimeException> failure = new AtomicReference<>();
    Model delegate =
        mode == ModelMode.DEEPSEEK
            ? DeepSeekModelFactory.deepSeekModel()
            : new DemoModel(role, workspaceId != null, scenario, fileScopes);
    Model controlled = new ControlledModel(delegate, mode, runtime, failure);
    AgentRoleDefinition definition = assembly.definition();
    var selectedSkills = assembly.skills();
    runtime.skillsLoaded(selectedSkills.stream().map(skill -> skill.reference()).toList());
    String skillGuidance =
        selectedSkills.stream()
            .map(skill -> "【" + skill.name() + "】" + skill.guidance())
            .collect(java.util.stream.Collectors.joining("\n"));
    var allowed = assembly.toolVersions().keySet();
    Toolkit toolkit = new Toolkit();
    var registered = new java.util.EnumMap<ToolKind, Integer>(ToolKind.class);
    if (allowed.contains(ToolKind.SIMULATED_INSPECTION)) {
      toolkit.registerTool(new AgentTool(tool, scenario, json, runtime, failure));
      registered.put(ToolKind.SIMULATED_INSPECTION, ToolContracts.SIMULATED_VERSION);
    }
    if (allowed.contains(ToolKind.WORKSPACE_READ)) {
      if (fileScopes.size() == 1)
        toolkit.registerTool(
            new WorkspaceReadTool(
                workspaces, json, workspaceId, fileScopes.getFirst(), runtime, failure));
      else
        toolkit.registerTool(
            new WorkspaceScopedReadTool(
                workspaces, workspaceId, scopedFiles, json, runtime, failure));
      registered.put(
          ToolKind.WORKSPACE_READ,
          ToolContracts.versionFor(ToolKind.WORKSPACE_READ, fileScopes.size()));
    }
    if (allowed.contains(ToolKind.WORKSPACE_WRITE)) {
      if (fileScopes.size() == 1)
        toolkit.registerTool(
            new WorkspaceWriteTool(
                workspaces, workspaceId, fileScopes.getFirst(), json, runtime, failure));
      else
        toolkit.registerTool(
            new WorkspaceScopedWriteTool(
                workspaces, workspaceId, scopedFiles, json, runtime, failure));
      registered.put(
          ToolKind.WORKSPACE_WRITE,
          ToolContracts.versionFor(ToolKind.WORKSPACE_WRITE, fileScopes.size()));
    }
    if (allowed.contains(ToolKind.WORKSPACE_SEARCH)) {
      toolkit.registerTool(
          new WorkspaceSearchTool(
              new AuthorizedCodeSearch(workspaces),
              workspaceId,
              scopedFiles,
              json,
              runtime,
              failure));
      registered.put(ToolKind.WORKSPACE_SEARCH, ToolContracts.SEARCH_VERSION);
    }
    if (!registered.equals(assembly.toolVersions()))
      throw new IllegalStateException("Agent 工具装配或契约版本不完整。");
    runtime.toolsLoaded(java.util.Map.copyOf(registered));
    try (ReActAgent agent =
        ReActAgent.builder()
            .name(definition.name())
            .sysPrompt(definition.systemPrompt() + "\n\n内置技能方法（不授予额外权限）：\n" + skillGuidance)
            .model(controlled)
            .stateStore(stateStore)
            .defaultSessionId(agentId)
            .conflictPolicy(ConflictPolicy.FAIL)
            .toolkit(toolkit)
            .maxIters(20)
            .maxRetries(1)
            .build()) {
      var future = agent.call(goal).toFuture();
      try {
        while (true) {
          runtime.check();
          if (failure.get() != null) throw failure.get();
          try {
            Msg response = future.get(200, TimeUnit.MILLISECONDS);
            if (failure.get() != null) throw failure.get();
            if (response == null
                || response.getTextContent() == null
                || response.getTextContent().isBlank())
              throw new IllegalStateException("AgentScope 未返回有效的最终回答。");
            return response.getTextContent();
          } catch (TimeoutException ignored) {
            /* 周期检查取消和时间限制。 */
          } catch (ExecutionException e) {
            if (failure.get() != null) throw failure.get();
            throw new IllegalStateException("AgentScope 执行失败，请检查模型、网络或工具状态。", e);
          }
        }
      } finally {
        future.cancel(true);
      }
    }
  }
}
