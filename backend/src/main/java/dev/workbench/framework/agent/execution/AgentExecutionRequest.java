package dev.workbench.framework.agent.execution;

import dev.workbench.domain.AgentRole;
import dev.workbench.domain.CheckpointStage;
import dev.workbench.domain.ModelMode;
import dev.workbench.domain.Scenario;
import dev.workbench.framework.agent.definition.AgentDefinitionRef;
import dev.workbench.framework.context.AgentInput;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** 一次角色实例调用的固定输入；不因角色切换而创建新的全局运行预算。 */
public record AgentExecutionRequest(
    AgentRole role,
    String agentId,
    AgentInput context,
    String workspaceId,
    List<String> fileScopes,
    boolean writeEnabled,
    Scenario scenario,
    ModelMode modelMode,
    CheckpointStage stage,
    String previous,
    AgentRuntime runtime,
    AgentDefinitionRef definitionRef,
    String expectedDefinitionSha256,
    List<String> writeFiles,
    String modelConnectionId,
    String locale,
    List<dev.workbench.framework.agent.assembly.AgentAssemblySnapshot.MemoryMatchView>
        resolvedMemories) {

  /** 兼容未预解析记忆的直跑路径；装配时再回退按 runId 查询。 */
  public AgentExecutionRequest(
      AgentRole role,
      String agentId,
      AgentInput context,
      String workspaceId,
      List<String> fileScopes,
      boolean writeEnabled,
      Scenario scenario,
      ModelMode modelMode,
      CheckpointStage stage,
      String previous,
      AgentRuntime runtime,
      AgentDefinitionRef definitionRef,
      String expectedDefinitionSha256,
      List<String> writeFiles,
      String modelConnectionId,
      String locale) {
    this(
        role,
        agentId,
        context,
        workspaceId,
        fileScopes,
        writeEnabled,
        scenario,
        modelMode,
        stage,
        previous,
        runtime,
        definitionRef,
        expectedDefinitionSha256,
        writeFiles,
        modelConnectionId,
        locale,
        null);
  }
  /** 旧调用没有语言偏好；默认中文，保持历史行为。 */
  public AgentExecutionRequest(
      AgentRole role,
      String agentId,
      AgentInput context,
      String workspaceId,
      List<String> fileScopes,
      boolean writeEnabled,
      Scenario scenario,
      ModelMode modelMode,
      CheckpointStage stage,
      String previous,
      AgentRuntime runtime,
      AgentDefinitionRef definitionRef,
      String expectedDefinitionSha256,
      List<String> writeFiles,
      String modelConnectionId) {
    this(
        role,
        agentId,
        context,
        workspaceId,
        fileScopes,
        writeEnabled,
        scenario,
        modelMode,
        stage,
        previous,
        runtime,
        definitionRef,
        expectedDefinitionSha256,
        writeFiles,
        modelConnectionId,
        null);
  }

  /** 旧调用没有用户模型连接；保持内置 mock / deepseek 行为。 */
  public AgentExecutionRequest(
      AgentRole role,
      String agentId,
      AgentInput context,
      String workspaceId,
      List<String> fileScopes,
      boolean writeEnabled,
      Scenario scenario,
      ModelMode modelMode,
      CheckpointStage stage,
      String previous,
      AgentRuntime runtime,
      AgentDefinitionRef definitionRef,
      String expectedDefinitionSha256,
      List<String> writeFiles) {
    this(
        role,
        agentId,
        context,
        workspaceId,
        fileScopes,
        writeEnabled,
        scenario,
        modelMode,
        stage,
        previous,
        runtime,
        definitionRef,
        expectedDefinitionSha256,
        writeFiles,
        null);
  }

  /** 旧调用没有节点级写范围时，写入授权覆盖整个读取范围。 */
  public AgentExecutionRequest(
      AgentRole role,
      String agentId,
      AgentInput context,
      String workspaceId,
      List<String> fileScopes,
      boolean writeEnabled,
      Scenario scenario,
      ModelMode modelMode,
      CheckpointStage stage,
      String previous,
      AgentRuntime runtime,
      AgentDefinitionRef definitionRef,
      String expectedDefinitionSha256) {
    this(
        role,
        agentId,
        context,
        workspaceId,
        fileScopes,
        writeEnabled,
        scenario,
        modelMode,
        stage,
        previous,
        runtime,
        definitionRef,
        expectedDefinitionSha256,
        writeEnabled ? fileScopes : List.of());
  }

  public AgentExecutionRequest(
      AgentRole role,
      String agentId,
      String input,
      String workspaceId,
      List<String> fileScopes,
      boolean writeEnabled,
      Scenario scenario,
      ModelMode modelMode,
      CheckpointStage stage,
      String previous,
      AgentRuntime runtime,
      AgentDefinitionRef definitionRef,
      String expectedDefinitionSha256) {
    this(
        role,
        agentId,
        new AgentInput(input, List.of()),
        workspaceId,
        fileScopes,
        writeEnabled,
        scenario,
        modelMode,
        stage,
        previous,
        runtime,
        definitionRef,
        expectedDefinitionSha256);
  }

  public String input() {
    return context.text();
  }

  /** 内部受信调用可按定义引用执行，不将它当作持久化计划。 */
  public AgentExecutionRequest(
      AgentRole role,
      String agentId,
      String input,
      String workspaceId,
      List<String> fileScopes,
      boolean writeEnabled,
      Scenario scenario,
      ModelMode modelMode,
      CheckpointStage stage,
      String previous,
      AgentRuntime runtime,
      AgentDefinitionRef definitionRef) {
    this(
        role,
        agentId,
        input,
        workspaceId,
        fileScopes,
        writeEnabled,
        scenario,
        modelMode,
        stage,
        previous,
        runtime,
        definitionRef,
        null);
  }

  /** 旧调用未指定定义时，沿用角色到内置定义的映射。 */
  public AgentExecutionRequest(
      AgentRole role,
      String agentId,
      String input,
      String workspaceId,
      List<String> fileScopes,
      boolean writeEnabled,
      Scenario scenario,
      ModelMode modelMode,
      CheckpointStage stage,
      String previous,
      AgentRuntime runtime) {
    this(
        role,
        agentId,
        input,
        workspaceId,
        fileScopes,
        writeEnabled,
        scenario,
        modelMode,
        stage,
        previous,
        runtime,
        null);
  }

  public AgentExecutionRequest withModelMode(ModelMode modelMode) {
    return new AgentExecutionRequest(
        role,
        agentId,
        context,
        workspaceId,
        fileScopes,
        writeEnabled,
        scenario,
        modelMode,
        stage,
        previous,
        runtime,
        definitionRef,
        expectedDefinitionSha256,
        writeFiles,
        modelMode == ModelMode.USER ? modelConnectionId : null,
        locale,
        resolvedMemories);
  }

  /** 把调度器一次性解析出的记忆证据挂到请求上；执行器不再二次查库。 */
  public AgentExecutionRequest withResolvedMemories(
      List<dev.workbench.framework.agent.assembly.AgentAssemblySnapshot.MemoryMatchView> memories) {
    return new AgentExecutionRequest(
        role,
        agentId,
        context,
        workspaceId,
        fileScopes,
        writeEnabled,
        scenario,
        modelMode,
        stage,
        previous,
        runtime,
        definitionRef,
        expectedDefinitionSha256,
        writeFiles,
        modelConnectionId,
        locale,
        memories);
  }

  public AgentExecutionRequest withModelConnection(String connectionId) {
    if (java.util.Objects.equals(modelConnectionId, connectionId)) return this;
    return new AgentExecutionRequest(
        role,
        agentId,
        context,
        workspaceId,
        fileScopes,
        writeEnabled,
        scenario,
        modelMode,
        stage,
        previous,
        runtime,
        definitionRef,
        expectedDefinitionSha256,
        writeFiles,
        connectionId,
        locale,
        resolvedMemories);
  }

  /** 任务语言随执行请求向下传递；Prompt 与工具描述按此选择语言。 */
  public AgentExecutionRequest withLocale(String value) {
    return new AgentExecutionRequest(
        role,
        agentId,
        context,
        workspaceId,
        fileScopes,
        writeEnabled,
        scenario,
        modelMode,
        stage,
        previous,
        runtime,
        definitionRef,
        expectedDefinitionSha256,
        writeFiles,
        modelConnectionId,
        value,
        resolvedMemories);
  }

  public AgentExecutionRequest {
    Objects.requireNonNull(role, "角色不能为空");
    UUID.fromString(agentId);
    if (context == null || context.text().isBlank())
      throw new IllegalArgumentException("Agent 输入不能为空。");
    if (fileScopes == null
        || (workspaceId == null && !fileScopes.isEmpty())
        || (workspaceId != null && fileScopes.isEmpty())
        || (workspaceId == null && writeEnabled)) {
      throw new IllegalArgumentException("工作区授权文件范围无效。");
    }
    fileScopes = List.copyOf(fileScopes);
    writeFiles = writeFiles == null ? List.of() : List.copyOf(writeFiles);
    if (!fileScopes.containsAll(writeFiles) || writeEnabled != !writeFiles.isEmpty())
      throw new IllegalArgumentException("节点写入范围必须属于读取范围并显式授权。");
    Objects.requireNonNull(scenario, "场景不能为空");
    Objects.requireNonNull(modelMode, "模型模式不能为空");
    Objects.requireNonNull(stage, "检查点阶段不能为空");
    Objects.requireNonNull(runtime, "运行控制不能为空");
    if (modelConnectionId != null
        && (modelMode != ModelMode.USER || !modelConnectionId.matches("[A-Za-z0-9-]{1,36}")))
      throw new IllegalArgumentException("用户模型连接标识无效。");
    if (expectedDefinitionSha256 != null
        && (definitionRef == null || !expectedDefinitionSha256.matches("[0-9a-f]{64}"))) {
      throw new IllegalArgumentException("计划中的 Agent 定义摘要无效。");
    }
    locale =
        locale == null || locale.isBlank()
            ? "zh-CN"
            : locale.strip().toLowerCase(java.util.Locale.ROOT).startsWith("en")
                ? "en-US"
                : "zh-CN";
    resolvedMemories = resolvedMemories == null ? null : List.copyOf(resolvedMemories);
  }
}
