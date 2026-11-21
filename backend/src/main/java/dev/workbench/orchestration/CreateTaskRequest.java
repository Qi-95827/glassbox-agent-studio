package dev.workbench.orchestration;

import com.fasterxml.jackson.annotation.JsonIgnore;
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
    Map<String, WorkflowNodeBudget> nodeBudgets,
    List<String> directoryScopes,
    List<String> excludedPaths,
    Long maxCostMicros) {

  /**
   * 兼容历史测试调用点的前缀参数写法。
   *
   * <p>所有旧构造器都是规范参数的“前缀 + 默认 null”，这里按长度统一收敛；生产代码与新测试应统一使用 {@link #builder()}。
   */
  @Deprecated(forRemoval = true)
  @JsonIgnore
  @SuppressWarnings("unchecked")
  public CreateTaskRequest(Object... values) {
    this(
        text(validated(values), 0),
        text(values, 1),
        integer(values, 2),
        integer(values, 3),
        integer(values, 4),
        text(values, 5),
        text(values, 6),
        text(values, 7),
        bool(values, 8),
        text(values, 9),
        bool(values, 10),
        integer(values, 11),
        cast(values, 12, List.class),
        cast(values, 13, List.class),
        longValue(values, 14),
        integer(values, 15),
        cast(values, 16, TaskEntryKind.class),
        integer(values, 17),
        integer(values, 18),
        cast(values, 19, List.class),
        cast(values, 20, Map.class),
        cast(values, 21, Map.class),
        text(values, 22),
        bool(values, 23),
        cast(values, 24, Map.class),
        cast(values, 25, List.class),
        cast(values, 26, List.class),
        longValue(values, 27));
  }

  public static Builder builder() {
    return new Builder();
  }

  private static Object[] validated(Object[] values) {
    if (values != null && values.length > 28)
      throw new IllegalArgumentException(
          "CreateTaskRequest 参数过多：" + values.length + "（最多 28 个，请改用 builder()）。");
    return values;
  }

  private static Object value(Object[] values, int index) {
    return values != null && index < values.length ? values[index] : null;
  }

  @SuppressWarnings("unchecked")
  private static <T> T cast(Object[] values, int index, Class<T> type) {
    Object raw = value(values, index);
    if (raw == null) return null;
    if (!type.isInstance(raw))
      throw new IllegalArgumentException(
          "CreateTaskRequest 第 "
              + index
              + " 个参数类型不匹配：期望 "
              + type.getSimpleName()
              + "，实际 "
              + raw.getClass().getSimpleName());
    return (T) raw;
  }

  private static String text(Object[] values, int index) {
    return cast(values, index, String.class);
  }

  private static Integer integer(Object[] values, int index) {
    return cast(values, index, Integer.class);
  }

  private static Long longValue(Object[] values, int index) {
    return cast(values, index, Long.class);
  }

  private static Boolean bool(Object[] values, int index) {
    return cast(values, index, Boolean.class);
  }

  /** 显式 builder，避免继续增加位置参数构造器。 */
  public static final class Builder {
    private String goal;
    private String scenario;
    private Integer maxRounds;
    private Integer maxTools;
    private Integer maxSeconds;
    private String modelMode;
    private String workspaceId;
    private String fileScope;
    private Boolean writeEnabled;
    private String bugId;
    private Boolean memoryEnabled;
    private Integer maxPerTool;
    private List<String> constraints;
    private List<String> acceptanceCriteria;
    private Long maxReportedTokens;
    private Integer maxIdenticalTools;
    private TaskEntryKind entryKind;
    private Integer maxAgentRounds;
    private Integer maxAgentTools;
    private List<String> fileScopes;
    private Map<AgentRole, RoleBudget> roleBudgets;
    private Map<ToolKind, Integer> toolBudgets;
    private String workflowTemplate;
    private Boolean reviewPlan;
    private Map<String, WorkflowNodeBudget> nodeBudgets;
    private List<String> directoryScopes;
    private List<String> excludedPaths;
    private Long maxCostMicros;

    public Builder goal(String value) {
      goal = value;
      return this;
    }

    public Builder scenario(String value) {
      scenario = value;
      return this;
    }

    public Builder limits(Integer rounds, Integer tools, Integer seconds) {
      maxRounds = rounds;
      maxTools = tools;
      maxSeconds = seconds;
      return this;
    }

    public Builder modelMode(String value) {
      modelMode = value;
      return this;
    }

    public Builder workspace(String id, String scope) {
      workspaceId = id;
      fileScope = scope;
      return this;
    }

    public Builder writeEnabled(Boolean value) {
      writeEnabled = value;
      return this;
    }

    public Builder bugId(String value) {
      bugId = value;
      return this;
    }

    public Builder memoryEnabled(Boolean value) {
      memoryEnabled = value;
      return this;
    }

    public Builder maxPerTool(Integer value) {
      maxPerTool = value;
      return this;
    }

    public Builder constraints(List<String> value) {
      constraints = value;
      return this;
    }

    public Builder acceptanceCriteria(List<String> value) {
      acceptanceCriteria = value;
      return this;
    }

    public Builder maxReportedTokens(Long value) {
      maxReportedTokens = value;
      return this;
    }

    public Builder maxIdenticalTools(Integer value) {
      maxIdenticalTools = value;
      return this;
    }

    public Builder entryKind(TaskEntryKind value) {
      entryKind = value;
      return this;
    }

    public Builder agentLimits(Integer rounds, Integer tools) {
      maxAgentRounds = rounds;
      maxAgentTools = tools;
      return this;
    }

    public Builder fileScopes(List<String> value) {
      fileScopes = value;
      return this;
    }

    public Builder roleBudgets(Map<AgentRole, RoleBudget> value) {
      roleBudgets = value;
      return this;
    }

    public Builder toolBudgets(Map<ToolKind, Integer> value) {
      toolBudgets = value;
      return this;
    }

    public Builder workflowTemplate(String value) {
      workflowTemplate = value;
      return this;
    }

    public Builder reviewPlan(Boolean value) {
      reviewPlan = value;
      return this;
    }

    public Builder nodeBudgets(Map<String, WorkflowNodeBudget> value) {
      nodeBudgets = value;
      return this;
    }

    public Builder directoryScopes(List<String> value) {
      directoryScopes = value;
      return this;
    }

    public Builder excludedPaths(List<String> value) {
      excludedPaths = value;
      return this;
    }

    public Builder maxCostMicros(Long value) {
      maxCostMicros = value;
      return this;
    }

    public CreateTaskRequest build() {
      return new CreateTaskRequest(
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
          workflowTemplate,
          reviewPlan,
          nodeBudgets,
          directoryScopes,
          excludedPaths,
          maxCostMicros);
    }
  }
}
