package dev.workbench.domain;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 运行所需的任务目标、模拟场景与模型模式。 */
public record TaskDefinition(
    UUID id,
    String goal,
    Scenario scenario,
    ModelMode modelMode,
    String workspaceId,
    String fileScope,
    boolean writeEnabled,
    String bugId,
    boolean memoryEnabled,
    List<String> constraints,
    List<String> acceptanceCriteria,
    TaskEntryKind entryKind,
    List<String> fileScopes,
    Map<AgentRole, RoleBudget> roleBudgets,
    Map<ToolKind, Integer> toolBudgets,
    String modelConnectionId) {
  /** 兼容未绑定用户模型连接的任务。 */
  public TaskDefinition(
      UUID id,
      String goal,
      Scenario scenario,
      ModelMode modelMode,
      String workspaceId,
      String fileScope,
      boolean writeEnabled,
      String bugId,
      boolean memoryEnabled,
      List<String> constraints,
      List<String> acceptanceCriteria,
      TaskEntryKind entryKind,
      List<String> fileScopes,
      Map<AgentRole, RoleBudget> roleBudgets,
      Map<ToolKind, Integer> toolBudgets) {
    this(
        id,
        goal,
        scenario,
        modelMode,
        workspaceId,
        fileScope,
        writeEnabled,
        bugId,
        memoryEnabled,
        constraints,
        acceptanceCriteria,
        entryKind,
        fileScopes,
        roleBudgets,
        toolBudgets,
        null);
  }

  /** 兼容尚未提供工具类型限额的任务。 */
  public TaskDefinition(
      UUID id,
      String goal,
      Scenario scenario,
      ModelMode modelMode,
      String workspaceId,
      String fileScope,
      boolean writeEnabled,
      String bugId,
      boolean memoryEnabled,
      List<String> constraints,
      List<String> acceptanceCriteria,
      TaskEntryKind entryKind,
      List<String> fileScopes,
      Map<AgentRole, RoleBudget> roleBudgets) {
    this(
        id,
        goal,
        scenario,
        modelMode,
        workspaceId,
        fileScope,
        writeEnabled,
        bugId,
        memoryEnabled,
        constraints,
        acceptanceCriteria,
        entryKind,
        fileScopes,
        roleBudgets,
        Map.of());
  }

  /** 兼容尚未提供角色限额的任务。 */
  public TaskDefinition(
      UUID id,
      String goal,
      Scenario scenario,
      ModelMode modelMode,
      String workspaceId,
      String fileScope,
      boolean writeEnabled,
      String bugId,
      boolean memoryEnabled,
      List<String> constraints,
      List<String> acceptanceCriteria,
      TaskEntryKind entryKind,
      List<String> fileScopes) {
    this(
        id,
        goal,
        scenario,
        modelMode,
        workspaceId,
        fileScope,
        writeEnabled,
        bugId,
        memoryEnabled,
        constraints,
        acceptanceCriteria,
        entryKind,
        fileScopes,
        Map.of());
  }

  /** 兼容历史单文件任务。 */
  public TaskDefinition(
      UUID id,
      String goal,
      Scenario scenario,
      ModelMode modelMode,
      String workspaceId,
      String fileScope,
      boolean writeEnabled,
      String bugId,
      boolean memoryEnabled,
      List<String> constraints,
      List<String> acceptanceCriteria,
      TaskEntryKind entryKind) {
    this(
        id,
        goal,
        scenario,
        modelMode,
        workspaceId,
        fileScope,
        writeEnabled,
        bugId,
        memoryEnabled,
        constraints,
        acceptanceCriteria,
        entryKind,
        fileScope == null ? List.of() : List.of(fileScope));
  }

  public TaskDefinition {
    if (entryKind == null) throw new IllegalArgumentException("任务入口类型不能为空。");
    constraints = List.copyOf(constraints);
    acceptanceCriteria = List.copyOf(acceptanceCriteria);
    fileScopes = List.copyOf(fileScopes);
    roleBudgets = Map.copyOf(roleBudgets);
    toolBudgets = Map.copyOf(toolBudgets);
    if (toolBudgets.values().stream().anyMatch(limit -> limit < 0 || limit > 20))
      throw new IllegalArgumentException("工具调用上限须在 0 至 20 之间。");
    if ((fileScopes.isEmpty() && fileScope != null)
        || (!fileScopes.isEmpty() && !fileScopes.getFirst().equals(fileScope)))
      throw new IllegalArgumentException("首个授权文件与文件集合不一致。");
    if (modelConnectionId != null
        && (modelMode != ModelMode.USER || !modelConnectionId.matches("[A-Za-z0-9-]{1,36}")))
      throw new IllegalArgumentException("用户模型连接标识无效。");
  }
}
