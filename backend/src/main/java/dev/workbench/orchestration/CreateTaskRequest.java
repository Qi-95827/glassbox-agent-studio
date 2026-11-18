package dev.workbench.orchestration;

import dev.workbench.domain.AgentRole;
import dev.workbench.domain.RoleBudget;
import dev.workbench.domain.TaskEntryKind;
import dev.workbench.domain.ToolKind;
import dev.workbench.framework.workflow.plan.WorkflowNodeBudget;
import java.util.List;
import java.util.Map;

/** HTTP 边界保留原有文本编码，创建任务时立即解析成领域枚举。 */
public record CreateTaskRequest(
    String goal,
    String scenario,
    Integer maxRounds,
    Integer maxTools,
    Integer maxSeconds,
    String modelMode,
    String workspaceId,
    String fileScope,
    Boolean writeEnabled,
    String bugId,
    Boolean memoryEnabled,
    Integer maxPerTool,
    List<String> constraints,
    List<String> acceptanceCriteria,
    Long maxReportedTokens,
    Integer maxIdenticalTools,
    TaskEntryKind entryKind,
    Integer maxAgentRounds,
    Integer maxAgentTools,
    List<String> fileScopes,
    @com.fasterxml.jackson.databind.annotation.JsonDeserialize(
            using = dev.workbench.domain.RoleBudgetMapDeserializer.class)
        Map<AgentRole, RoleBudget> roleBudgets,
    @com.fasterxml.jackson.databind.annotation.JsonDeserialize(
            using = dev.workbench.domain.ToolBudgetMapDeserializer.class)
        Map<ToolKind, Integer> toolBudgets,
    String workflowTemplate,
    Boolean reviewPlan,
    Map<String, WorkflowNodeBudget> nodeBudgets) {
  /** 兼容原有任务创建调用。 */
  public CreateTaskRequest(
      String goal,
      String scenario,
      Integer maxRounds,
      Integer maxTools,
      Integer maxSeconds,
      String modelMode,
      String workspaceId,
      String fileScope,
      Boolean writeEnabled,
      String bugId,
      Boolean memoryEnabled,
      Integer maxPerTool,
      List<String> constraints,
      List<String> acceptanceCriteria,
      Long maxReportedTokens,
      Integer maxIdenticalTools,
      TaskEntryKind entryKind,
      Integer maxAgentRounds,
      Integer maxAgentTools,
      List<String> fileScopes,
      Map<AgentRole, RoleBudget> roleBudgets,
      Map<ToolKind, Integer> toolBudgets) {
    this(
        goal,
        scenario,
        maxRounds,
        maxTools,
        maxSeconds,
        modelMode,
        workspaceId,
        fileScope,
        writeEnabled,
        bugId,
        memoryEnabled,
        maxPerTool,
        constraints,
        acceptanceCriteria,
        maxReportedTokens,
        maxIdenticalTools,
        entryKind,
        maxAgentRounds,
        maxAgentTools,
        fileScopes,
        roleBudgets,
        toolBudgets,
        null,
        null,
        null);
  }

  /** 兼容尚未提供工具类型限额的请求。 */
  public CreateTaskRequest(
      String goal,
      String scenario,
      Integer maxRounds,
      Integer maxTools,
      Integer maxSeconds,
      String modelMode,
      String workspaceId,
      String fileScope,
      Boolean writeEnabled,
      String bugId,
      Boolean memoryEnabled,
      Integer maxPerTool,
      List<String> constraints,
      List<String> acceptanceCriteria,
      Long maxReportedTokens,
      Integer maxIdenticalTools,
      TaskEntryKind entryKind,
      Integer maxAgentRounds,
      Integer maxAgentTools,
      List<String> fileScopes,
      Map<AgentRole, RoleBudget> roleBudgets) {
    this(
        goal,
        scenario,
        maxRounds,
        maxTools,
        maxSeconds,
        modelMode,
        workspaceId,
        fileScope,
        writeEnabled,
        bugId,
        memoryEnabled,
        maxPerTool,
        constraints,
        acceptanceCriteria,
        maxReportedTokens,
        maxIdenticalTools,
        entryKind,
        maxAgentRounds,
        maxAgentTools,
        fileScopes,
        roleBudgets,
        null);
  }

  /** 兼容不含角色限额的多文件请求。 */
  public CreateTaskRequest(
      String goal,
      String scenario,
      Integer maxRounds,
      Integer maxTools,
      Integer maxSeconds,
      String modelMode,
      String workspaceId,
      String fileScope,
      Boolean writeEnabled,
      String bugId,
      Boolean memoryEnabled,
      Integer maxPerTool,
      List<String> constraints,
      List<String> acceptanceCriteria,
      Long maxReportedTokens,
      Integer maxIdenticalTools,
      TaskEntryKind entryKind,
      Integer maxAgentRounds,
      Integer maxAgentTools,
      List<String> fileScopes) {
    this(
        goal,
        scenario,
        maxRounds,
        maxTools,
        maxSeconds,
        modelMode,
        workspaceId,
        fileScope,
        writeEnabled,
        bugId,
        memoryEnabled,
        maxPerTool,
        constraints,
        acceptanceCriteria,
        maxReportedTokens,
        maxIdenticalTools,
        entryKind,
        maxAgentRounds,
        maxAgentTools,
        fileScopes,
        null);
  }

  /** 兼容原有单文件请求。 */
  public CreateTaskRequest(
      String goal,
      String scenario,
      Integer maxRounds,
      Integer maxTools,
      Integer maxSeconds,
      String modelMode,
      String workspaceId,
      String fileScope,
      Boolean writeEnabled,
      String bugId,
      Boolean memoryEnabled,
      Integer maxPerTool,
      List<String> constraints,
      List<String> acceptanceCriteria,
      Long maxReportedTokens,
      Integer maxIdenticalTools,
      TaskEntryKind entryKind,
      Integer maxAgentRounds,
      Integer maxAgentTools) {
    this(
        goal,
        scenario,
        maxRounds,
        maxTools,
        maxSeconds,
        modelMode,
        workspaceId,
        fileScope,
        writeEnabled,
        bugId,
        memoryEnabled,
        maxPerTool,
        constraints,
        acceptanceCriteria,
        maxReportedTokens,
        maxIdenticalTools,
        entryKind,
        maxAgentRounds,
        maxAgentTools,
        null);
  }

  /** 兼容未配置单实例限额的调用。 */
  public CreateTaskRequest(
      String goal,
      String scenario,
      Integer maxRounds,
      Integer maxTools,
      Integer maxSeconds,
      String modelMode,
      String workspaceId,
      String fileScope,
      Boolean writeEnabled,
      String bugId,
      Boolean memoryEnabled,
      Integer maxPerTool,
      List<String> constraints,
      List<String> acceptanceCriteria,
      Long maxReportedTokens,
      Integer maxIdenticalTools,
      TaskEntryKind entryKind) {
    this(
        goal,
        scenario,
        maxRounds,
        maxTools,
        maxSeconds,
        modelMode,
        workspaceId,
        fileScope,
        writeEnabled,
        bugId,
        memoryEnabled,
        maxPerTool,
        constraints,
        acceptanceCriteria,
        maxReportedTokens,
        maxIdenticalTools,
        entryKind,
        null,
        null);
  }

  /** 兼容已有内部调用；没有指定时使用通用入口。 */
  public CreateTaskRequest(
      String goal,
      String scenario,
      Integer maxRounds,
      Integer maxTools,
      Integer maxSeconds,
      String modelMode,
      String workspaceId,
      String fileScope,
      Boolean writeEnabled,
      String bugId,
      Boolean memoryEnabled,
      Integer maxPerTool,
      List<String> constraints,
      List<String> acceptanceCriteria,
      Long maxReportedTokens,
      Integer maxIdenticalTools) {
    this(
        goal,
        scenario,
        maxRounds,
        maxTools,
        maxSeconds,
        modelMode,
        workspaceId,
        fileScope,
        writeEnabled,
        bugId,
        memoryEnabled,
        maxPerTool,
        constraints,
        acceptanceCriteria,
        maxReportedTokens,
        maxIdenticalTools,
        null);
  }

  public CreateTaskRequest(
      String goal,
      String scenario,
      Integer maxRounds,
      Integer maxTools,
      Integer maxSeconds,
      String modelMode,
      String workspaceId,
      String fileScope,
      Boolean writeEnabled,
      String bugId,
      Boolean memoryEnabled,
      Integer maxPerTool,
      List<String> constraints,
      List<String> acceptanceCriteria,
      Long maxReportedTokens) {
    this(
        goal,
        scenario,
        maxRounds,
        maxTools,
        maxSeconds,
        modelMode,
        workspaceId,
        fileScope,
        writeEnabled,
        bugId,
        memoryEnabled,
        maxPerTool,
        constraints,
        acceptanceCriteria,
        maxReportedTokens,
        null);
  }

  public CreateTaskRequest(
      String goal,
      String scenario,
      Integer maxRounds,
      Integer maxTools,
      Integer maxSeconds,
      String modelMode,
      String workspaceId,
      String fileScope,
      Boolean writeEnabled,
      String bugId,
      Boolean memoryEnabled,
      Integer maxPerTool,
      List<String> constraints,
      List<String> acceptanceCriteria) {
    this(
        goal,
        scenario,
        maxRounds,
        maxTools,
        maxSeconds,
        modelMode,
        workspaceId,
        fileScope,
        writeEnabled,
        bugId,
        memoryEnabled,
        maxPerTool,
        constraints,
        acceptanceCriteria,
        null);
  }

  public CreateTaskRequest(
      String goal,
      String scenario,
      Integer maxRounds,
      Integer maxTools,
      Integer maxSeconds,
      String modelMode,
      String workspaceId,
      String fileScope,
      Boolean writeEnabled,
      String bugId,
      Boolean memoryEnabled,
      Integer maxPerTool) {
    this(
        goal,
        scenario,
        maxRounds,
        maxTools,
        maxSeconds,
        modelMode,
        workspaceId,
        fileScope,
        writeEnabled,
        bugId,
        memoryEnabled,
        maxPerTool,
        List.of(),
        List.of());
  }

  public CreateTaskRequest(
      String goal, String scenario, Integer maxRounds, Integer maxTools, Integer maxSeconds) {
    this(
        goal,
        scenario,
        maxRounds,
        maxTools,
        maxSeconds,
        "mock",
        null,
        null,
        false,
        null,
        false,
        null);
  }

  public CreateTaskRequest(
      String goal,
      String scenario,
      Integer maxRounds,
      Integer maxTools,
      Integer maxSeconds,
      String modelMode) {
    this(
        goal,
        scenario,
        maxRounds,
        maxTools,
        maxSeconds,
        modelMode,
        null,
        null,
        false,
        null,
        false,
        null);
  }

  public CreateTaskRequest(
      String goal,
      String scenario,
      Integer maxRounds,
      Integer maxTools,
      Integer maxSeconds,
      String modelMode,
      String workspaceId,
      String fileScope,
      Boolean writeEnabled,
      String bugId) {
    this(
        goal,
        scenario,
        maxRounds,
        maxTools,
        maxSeconds,
        modelMode,
        workspaceId,
        fileScope,
        writeEnabled,
        bugId,
        false,
        null);
  }

  public CreateTaskRequest(
      String goal,
      String scenario,
      Integer maxRounds,
      Integer maxTools,
      Integer maxSeconds,
      String modelMode,
      String workspaceId,
      String fileScope,
      Boolean writeEnabled,
      String bugId,
      Boolean memoryEnabled) {
    this(
        goal,
        scenario,
        maxRounds,
        maxTools,
        maxSeconds,
        modelMode,
        workspaceId,
        fileScope,
        writeEnabled,
        bugId,
        memoryEnabled,
        null);
  }
}
