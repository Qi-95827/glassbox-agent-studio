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
    List<String> writeFiles) {
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
        writeFiles);
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
    if (expectedDefinitionSha256 != null
        && (definitionRef == null || !expectedDefinitionSha256.matches("[0-9a-f]{64}"))) {
      throw new IllegalArgumentException("计划中的 Agent 定义摘要无效。");
    }
  }
}
