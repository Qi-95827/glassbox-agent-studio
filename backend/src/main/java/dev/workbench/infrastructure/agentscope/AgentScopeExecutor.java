package dev.workbench.infrastructure.agentscope;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.workbench.domain.CheckpointStage;
import dev.workbench.framework.agent.assembly.AgentAssemblyPlanner;
import dev.workbench.framework.agent.assembly.AgentAssemblySnapshot;
import dev.workbench.framework.agent.definition.AgentDefinition;
import dev.workbench.framework.agent.execution.AgentExecutionRequest;
import dev.workbench.framework.agent.execution.AgentExecutor;
import dev.workbench.framework.model.AgentModelRegistry;
import dev.workbench.framework.tool.AgentToolContext;
import dev.workbench.workspace.AuthorizedFileSet;
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
public class AgentScopeExecutor implements AgentExecutor {
  private static final int MAX_FRAMEWORK_RETRIES = 1;
  private final ObjectMapper json;
  private final AgentStateStore stateStore;
  private final AgentAssemblyPlanner assemblyPlanner;
  private final AgentModelRegistry models;
  private final AgentToolRegistry tools;
  private final dev.workbench.framework.agent.definition.AgentRoleRegistry roles;
  private final dev.workbench.framework.skill.SkillRegistry skills;
  private final dev.workbench.control.MultiTierContextCompactor contextCompactor;
  private final org.springframework.beans.factory.ObjectProvider<
          dev.workbench.framework.agent.assembly.MemoryAssemblyPort>
      memoryPorts;
  private final org.springframework.beans.factory.ObjectProvider<
          dev.workbench.infrastructure.mcp.McpToolProvider>
      mcpProviders;
  private final org.springframework.beans.factory.ObjectProvider<
          dev.workbench.infrastructure.mcp.McpClientManager>
      mcpClients;
  private final org.springframework.beans.factory.ObjectProvider<
          dev.workbench.ownership.OwnershipService>
      ownership;

  @org.springframework.beans.factory.annotation.Autowired
  public AgentScopeExecutor(
      ObjectMapper json,
      AgentStateStore stateStore,
      AgentAssemblyPlanner assemblyPlanner,
      AgentModelRegistry models,
      AgentToolRegistry tools,
      dev.workbench.framework.agent.definition.AgentRoleRegistry roles,
      dev.workbench.framework.skill.SkillRegistry skills,
      dev.workbench.control.MultiTierContextCompactor contextCompactor,
      org.springframework.beans.factory.ObjectProvider<
              dev.workbench.framework.agent.assembly.MemoryAssemblyPort>
          memoryPorts,
      org.springframework.beans.factory.ObjectProvider<
              dev.workbench.infrastructure.mcp.McpToolProvider>
          mcpProviders,
      org.springframework.beans.factory.ObjectProvider<
              dev.workbench.infrastructure.mcp.McpClientManager>
          mcpClients,
      org.springframework.beans.factory.ObjectProvider<dev.workbench.ownership.OwnershipService>
          ownership) {
    this.json = json;
    this.stateStore = stateStore;
    this.assemblyPlanner = assemblyPlanner;
    this.models = models;
    this.tools = tools;
    this.roles = roles;
    this.skills = skills;
    this.contextCompactor = contextCompactor;
    this.memoryPorts = memoryPorts;
    this.mcpProviders = mcpProviders;
    this.mcpClients = mcpClients;
    this.ownership = ownership;
  }

  /** 兼容只装配执行必需依赖的独立验证；语言资源使用内置中文默认。 */
  public AgentScopeExecutor(
      ObjectMapper json,
      AgentStateStore stateStore,
      AgentAssemblyPlanner assemblyPlanner,
      AgentModelRegistry models,
      AgentToolRegistry tools) {
    this(
        json,
        stateStore,
        assemblyPlanner,
        models,
        tools,
        new dev.workbench.framework.agent.definition.AgentRoleRegistry(),
        new dev.workbench.framework.skill.SkillRegistry(),
        new dev.workbench.control.MultiTierContextCompactor(),
        null,
        null,
        null,
        null);
  }

  /** 解析运行所属账号；仅供压缩上下文审计与 L1 产物隔离，解析失败不阻断执行。 */
  /**
   * 装配记录中的工具版本映射。
   *
   * <p>只有真正注册了外部工具的节点才写入 {@code ToolKind.MCP -> 1}：装配记录是运行时 {@code beforeTool}
   * 的唯一授权依据，多写会让未装配的节点越权，少写则所有 MCP 调用都会被拒绝。
   */
  static java.util.Map<dev.workbench.domain.ToolKind, Integer> toolVersions(
      java.util.Map<dev.workbench.domain.ToolKind, Integer> assemblyVersions,
      boolean mcpRegistered) {
    var versions =
        new java.util.EnumMap<dev.workbench.domain.ToolKind, Integer>(
            dev.workbench.domain.ToolKind.class);
    if (assemblyVersions != null) versions.putAll(assemblyVersions);
    if (mcpRegistered) versions.put(dev.workbench.domain.ToolKind.MCP, 1);
    return java.util.Map.copyOf(versions);
  }

  private String ownerScopeKey(String runId) {
    if (runId == null || runId.isBlank() || ownership == null) return null;
    var service = ownership.getIfAvailable();
    if (service == null) return null;
    try {
      String taskId = service.taskIdOfRun(runId);
      return service.owner(dev.workbench.ownership.OwnershipService.TASK, taskId).orElse(null);
    } catch (RuntimeException e) {
      return null;
    }
  }

  /** 创建每次运行独立的框架 Agent；工具按角色及用户授权注册。 */
  @Override
  public String execute(AgentExecutionRequest request) throws InterruptedException {
    var role = request.role();
    var agentId = request.agentId();
    var goal = request.input();
    var workspaceId = request.workspaceId();
    var fileScopes = request.fileScopes();
    var writeFiles = request.writeFiles();
    var scenario = request.scenario();
    var stage = request.stage();
    var runtime = request.runtime();
    if (stage != CheckpointStage.BEFORE_TOOL)
      throw new IllegalArgumentException("当前仅支持从 Agent 启动前恢复。");
    var scopedFiles =
        workspaceId == null ? List.<String>of() : new AuthorizedFileSet(fileScopes).paths();
    var scopedWrites =
        writeFiles.isEmpty() ? List.<String>of() : new AuthorizedFileSet(writeFiles).paths();
    var assembly =
        assemblyPlanner.plan(
            role,
            request.definitionRef(),
            request.expectedDefinitionSha256(),
            workspaceId != null,
            fileScopes.size(),
            writeFiles.size(),
            request.writeEnabled());
    var effectiveMode =
        assembly.modelOverride() == null ? request.modelMode() : assembly.modelOverride();
    var effectiveRequest = request.withModelMode(effectiveMode);
    AtomicReference<RuntimeException> failure = new AtomicReference<>();
    AgentDefinition definition = assembly.definition();
    var selectedSkills = assembly.skills();
    boolean english =
        dev.workbench.framework.prompt.PromptResourceLoader.isEnglish(request.locale());
    String skillGuidance =
        java.util.stream.Stream.concat(
                selectedSkills.stream()
                    .map(
                        skill ->
                            (english ? "[" : "【")
                                + skill.name()
                                + (english ? "] " : "】")
                                + skills.guidance(skill.id(), request.locale())),
                assembly.privateSkills().stream()
                    .map(
                        skill ->
                            (english ? "[" : "【")
                                + skill.name()
                                + " v"
                                + skill.version()
                                + (english ? "] " : "】")
                                + skill.guidance()))
            .collect(java.util.stream.Collectors.joining("\n"));
    String localizedSystemPrompt =
        roles.systemPrompt(role, request.locale())
            + "\n\n"
            + (english
                ? "Built-in skill playbooks (do not grant extra permissions):\n"
                : "内置技能方法（不授予额外权限）：\n")
            + skillGuidance;
    var toolContext =
        new AgentToolContext(
            json,
            workspaceId,
            scopedFiles,
            scenario,
            runtime,
            failure,
            scopedWrites,
            request.locale());
    Toolkit toolkit =
        assembly.privateTools().isEmpty()
            ? tools.assemble(assembly.toolVersions(), toolContext)
            : tools.assemblePrivate(assembly.privateTools(), toolContext);
    var mcpProvider = mcpProviders == null ? null : mcpProviders.getIfAvailable();
    var mcpManager = mcpClients == null ? null : mcpClients.getIfAvailable();
    // 只有真正注册了外部工具的节点才在装配记录中暴露 ToolKind.MCP，否则运行时 beforeTool 会误判越权。
    boolean mcpRegistered = false;
    if (mcpProvider != null && mcpManager != null && request.context().runId() != null) {
      for (var binding : mcpProvider.bindingsForRun(request.context().runId())) {
        String name = binding.exposedName();
        // 外部工具按节点角色收口：评审角色禁用，分析角色剥离高风险写工具。
        var admission =
            dev.workbench.infrastructure.mcp.McpToolAdmission.decide(
                request.role(), binding.riskLevel());
        if (!admission.allowed()) {
          runtime.event(
              dev.workbench.domain.EventType.TOOL_REJECTED,
              "未装配外部工具 " + name + "：" + admission.reason());
          continue;
        }
        if (toolkit.getTool(name) == null) {
          toolkit.registerAgentTool(new McpAgentTool(binding, mcpManager, toolContext));
          mcpRegistered = true;
        }
      }
    }
    if (mcpRegistered)
      runtime.event(
          dev.workbench.domain.EventType.TOOL_STARTED, "已装配外部 MCP 工具（版本 1），写操作仍须 Gate 5.5 审批。");
    var toolVersions = toolVersions(assembly.toolVersions(), mcpRegistered);
    var selectedModel = models.select(effectiveRequest);
    if (!(selectedModel.client().nativeClient() instanceof Model nativeModel))
      throw new IllegalStateException(
          "模型提供者 "
              + selectedModel.client().getClass().getSimpleName()
              + " 未返回 AgentScope 可执行适配器，不能装配本次运行。");
    Model controlled =
        new ControlledModel(
            nativeModel,
            effectiveMode,
            runtime,
            failure,
            selectedModel.rateLimitRejection(),
            contextCompactor::evaluateAndCompact,
            new dev.workbench.framework.context.CompactionScope(
                request.context().runId(),
                ownerScopeKey(request.context().runId()),
                request.modelConnectionId(),
                runtime::auxiliaryModelUsage),
            // 同一运行内的连续模型调用共享上一轮 L2 结构化检查点，供增量合并。
            new java.util.concurrent.atomic.AtomicReference<>());
    var authorization =
        new AgentAssemblySnapshot.Authorization(
            1,
            role,
            role.version(),
            workspaceId,
            scopedFiles.size(),
            scopedFiles.isEmpty() ? null : scopeDigest(scopedFiles),
            request.writeEnabled());
    var memoryPort = memoryPorts == null ? null : memoryPorts.getIfAvailable();
    var memoryMatches =
        memoryPort == null || request.context().runId() == null
            ? List.<AgentAssemblySnapshot.MemoryMatchView>of()
            : memoryPort.matchesForRun(request.context().runId());
    var components =
        new AgentAssemblySnapshot.Components(
            definition.ref(),
            assembly.definitionSha256(),
            selectedModel.reference(),
            null,
            MAX_FRAMEWORK_RETRIES,
            selectedSkills.stream().map(skill -> skill.reference()).toList(),
            toolVersions,
            authorization,
            new AgentAssemblySnapshot.Input(
                AgentAssemblySnapshot.sha256(goal), request.context().sources()),
            assembly.privateTools().stream()
                .map(
                    tool ->
                        new AgentAssemblySnapshot.PrivateTool(
                            tool.id(),
                            tool.version(),
                            tool.name(),
                            tool.toolName(),
                            tool.fileExtensions()))
                .toList(),
            memoryMatches);
    try (ReActAgent agent =
        ReActAgent.builder()
            .name(definition.name())
            .sysPrompt(localizedSystemPrompt)
            .model(controlled)
            .stateStore(stateStore)
            .defaultSessionId(agentId)
            .conflictPolicy(ConflictPolicy.FAIL)
            .toolkit(toolkit)
            // SDK 要求正整数且自身默认 10 次；实际用户限额统一由 RunControl 检查。
            .maxIters(Integer.MAX_VALUE)
            .maxRetries(MAX_FRAMEWORK_RETRIES)
            .build()) {
      runtime.assemblyResolved(components);
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

  private String scopeDigest(List<String> paths) {
    try {
      return AgentAssemblySnapshot.sha256(json.writeValueAsString(paths));
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException("无法计算工作区授权范围摘要。", e);
    }
  }
}
