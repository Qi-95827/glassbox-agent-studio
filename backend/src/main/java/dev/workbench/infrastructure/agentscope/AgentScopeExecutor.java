package dev.workbench.infrastructure.agentscope;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.workbench.domain.CheckpointStage;
import dev.workbench.framework.agent.assembly.AgentAssemblyPlanner;
import dev.workbench.framework.agent.assembly.AgentAssemblySnapshot;
import dev.workbench.framework.agent.definition.AgentDefinition;
import dev.workbench.framework.agent.execution.AgentExecutionRequest;
import dev.workbench.framework.agent.execution.AgentExecutor;
import dev.workbench.framework.model.AgentModelRegistry;
import dev.workbench.framework.model.ControlledModel;
import dev.workbench.framework.tool.AgentToolContext;
import dev.workbench.framework.tool.AgentToolRegistry;
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

  public AgentScopeExecutor(
      ObjectMapper json,
      AgentStateStore stateStore,
      AgentAssemblyPlanner assemblyPlanner,
      AgentModelRegistry models,
      AgentToolRegistry tools) {
    this.json = json;
    this.stateStore = stateStore;
    this.assemblyPlanner = assemblyPlanner;
    this.models = models;
    this.tools = tools;
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
    String skillGuidance =
        java.util.stream.Stream.concat(
                selectedSkills.stream().map(skill -> "【" + skill.name() + "】" + skill.guidance()),
                assembly.privateSkills().stream()
                    .map(
                        skill ->
                            "【" + skill.name() + " v" + skill.version() + "】" + skill.guidance()))
            .collect(java.util.stream.Collectors.joining("\n"));
    var toolContext =
        new AgentToolContext(
            json, workspaceId, scopedFiles, scenario, runtime, failure, scopedWrites);
    Toolkit toolkit =
        assembly.privateTools().isEmpty()
            ? tools.assemble(assembly.toolVersions(), toolContext)
            : tools.assemblePrivate(assembly.privateTools(), toolContext);
    var selectedModel = models.select(effectiveRequest);
    Model controlled =
        new ControlledModel(
            selectedModel.model(),
            effectiveMode,
            runtime,
            failure,
            selectedModel.rateLimitRejection());
    var authorization =
        new AgentAssemblySnapshot.Authorization(
            1,
            role,
            role.version(),
            workspaceId,
            scopedFiles.size(),
            scopedFiles.isEmpty() ? null : scopeDigest(scopedFiles),
            request.writeEnabled());
    var components =
        new AgentAssemblySnapshot.Components(
            definition.ref(),
            assembly.definitionSha256(),
            selectedModel.reference(),
            null,
            MAX_FRAMEWORK_RETRIES,
            selectedSkills.stream().map(skill -> skill.reference()).toList(),
            assembly.toolVersions(),
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
                .toList());
    try (ReActAgent agent =
        ReActAgent.builder()
            .name(definition.name())
            .sysPrompt(definition.systemPrompt() + "\n\n内置技能方法（不授予额外权限）：\n" + skillGuidance)
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
