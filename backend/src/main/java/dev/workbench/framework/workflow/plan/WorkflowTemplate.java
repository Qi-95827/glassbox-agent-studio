package dev.workbench.framework.workflow.plan;

import dev.workbench.domain.TaskDefinition;
import dev.workbench.domain.TaskEntryKind;
import dev.workbench.domain.ToolKind;
import dev.workbench.framework.agent.assembly.AgentAssemblySnapshot;
import dev.workbench.framework.agent.definition.AgentDefinitionRef;
import dev.workbench.framework.workflow.check.SystemCheckRef;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 由受信代码注册的版本化模板；每个步骤要么执行一个 Agent，要么执行一个系统检查。 */
public record WorkflowTemplate(
    String id, int version, Scope scope, int maxAttempts, List<Step> steps) {
  public WorkflowTemplate(String id, int version, List<Step> steps) {
    this(id, version, Scope.TEXT_ONLY, steps);
  }

  public WorkflowTemplate(String id, int version, Scope scope, List<Step> steps) {
    this(id, version, scope, 2, steps);
  }

  public WorkflowTemplate {
    if (id == null || !id.matches("[a-z0-9-]{1,64}") || version < 1)
      throw new IllegalArgumentException("工作流模板标识或版本无效。");
    Objects.requireNonNull(scope);
    if (maxAttempts < 1 || maxAttempts > 2) throw new IllegalArgumentException("节点尝试上限无效。");
    steps = List.copyOf(steps);
    new WorkflowDefinition(
        steps.stream()
            .map(
                step ->
                    new WorkflowDefinition.Node(
                        step.key(), step.agent(), step.check(), step.dependencies()))
            .toList());
  }

  /** 应用到此次计划的用户限额，节点身份和模板权限保持由服务端决定。 */
  public WorkflowTemplate withNodeBudgets(Map<String, WorkflowNodeBudget> overrides) {
    if (overrides == null || overrides.isEmpty()) return this;
    for (var entry : overrides.entrySet()) {
      if (entry.getValue() == null
          || steps.stream().noneMatch(step -> step.key().equals(entry.getKey())))
        throw new IllegalArgumentException("节点预算必须对应所选模板的节点。");
    }
    return new WorkflowTemplate(
        id,
        version,
        scope,
        maxAttempts,
        steps.stream()
            .map(
                step ->
                    new Step(
                        step.key(),
                        step.label(),
                        step.phase(),
                        step.agent(),
                        step.dependencies(),
                        overrides.getOrDefault(step.key(), step.budget()),
                        step.kind(),
                        step.check()))
            .toList());
  }

  /** 服务端定义的动态模板授权边界。 */
  public enum Scope {
    TEXT_ONLY,
    WORKSPACE_READ_ONLY,
    WORKSPACE_WRITE_AUTHORIZED,
    /** 模拟 Bug 修复：要求 Bug 入口、显式写入授权和工作区文件集合。 */
    WORKSPACE_BUG_FIX;

    public boolean hasWorkspace() {
      return this != TEXT_ONLY;
    }

    public boolean allowsWrite() {
      return this == WORKSPACE_WRITE_AUTHORIZED || this == WORKSPACE_BUG_FIX;
    }

    /** Bug 修复模板必须绑定明确的模拟 Bug 及 Bug 入口。 */
    public boolean requiresBug() {
      return this == WORKSPACE_BUG_FIX;
    }

    boolean permits(ToolKind tool) {
      return hasWorkspace()
          && (tool == ToolKind.WORKSPACE_READ
              || tool == ToolKind.WORKSPACE_SEARCH
              || (allowsWrite() && tool == ToolKind.WORKSPACE_WRITE));
    }

    public String authorizationDigest(TaskDefinition task) {
      if (task.memoryEnabled()) throw new IllegalArgumentException("所选动态模板不支持项目背景记忆。");
      if (this == WORKSPACE_BUG_FIX) {
        if (task.entryKind() != TaskEntryKind.BUG || task.bugId() == null)
          throw new IllegalArgumentException("Bug 修复工作流需要从 Bug 入口绑定模拟 Bug。");
        if (!task.writeEnabled()) throw new IllegalArgumentException("Bug 修复工作流需要显式授权修改文件。");
        if (task.workspaceId() == null || task.fileScopes().isEmpty())
          throw new IllegalArgumentException("所选工作流需要工作区和授权文件。");
        var fields = new StringBuilder("bug-fix-v1|").append(task.bugId());
        fields.append('|').append(task.workspaceId());
        for (var path : task.fileScopes())
          fields.append('|').append(path.length()).append(':').append(path);
        return AgentAssemblySnapshot.sha256(fields.toString());
      }
      if (task.bugId() != null || task.entryKind() == TaskEntryKind.BUG)
        throw new IllegalArgumentException("所选动态模板不支持 Bug 任务。");
      if (task.writeEnabled() != allowsWrite())
        throw new IllegalArgumentException(allowsWrite() ? "所选工作流需要显式授权修改文件。" : "所选只读工作流不允许写入。");
      if (this == TEXT_ONLY) {
        if (task.workspaceId() != null) throw new IllegalArgumentException("纯文本模板不能关联工作区。");
        return null;
      }
      if (task.workspaceId() == null || task.fileScopes().isEmpty())
        throw new IllegalArgumentException("所选工作流需要工作区和授权文件。");
      var fields = new StringBuilder(task.workspaceId());
      for (var path : task.fileScopes())
        fields.append('|').append(path.length()).append(':').append(path);
      return AgentAssemblySnapshot.sha256(fields.toString());
    }
  }

  /**
   * 计划步骤。
   *
   * <p>Agent 步骤携带定义引用；系统检查步骤携带受信检查引用，二者不能同时存在。检查步骤不申请工具， 也不会创建 Agent 实例。
   */
  public record Step(
      String key,
      String label,
      String phase,
      AgentDefinitionRef agent,
      List<String> dependencies,
      WorkflowNodeBudget budget,
      PlanNodeKind kind,
      SystemCheckRef check) {
    public Step(
        String key,
        String label,
        String phase,
        AgentDefinitionRef agent,
        List<String> dependencies) {
      this(key, label, phase, agent, dependencies, null, PlanNodeKind.AGENT, null);
    }

    public Step(
        String key,
        String label,
        String phase,
        AgentDefinitionRef agent,
        List<String> dependencies,
        WorkflowNodeBudget budget) {
      this(key, label, phase, agent, dependencies, budget, PlanNodeKind.AGENT, null);
    }

    /** 构造不创建 Agent 实例的系统检查步骤。 */
    public static Step systemCheck(
        String key, String label, String phase, SystemCheckRef check, List<String> dependencies) {
      return new Step(
          key, label, phase, null, dependencies, null, PlanNodeKind.SYSTEM_CHECK, check);
    }

    public Step {
      if (label == null
          || label.isBlank()
          || label.length() > 120
          || phase == null
          || !phase.matches("[a-z0-9-]{1,64}")) throw new IllegalArgumentException("工作流步骤名称或阶段无效。");
      Objects.requireNonNull(kind, "工作流步骤类型不能为空");
      if ((kind == PlanNodeKind.AGENT) != (agent != null) || (agent != null) != (check == null))
        throw new IllegalArgumentException("工作流步骤必须且只能选择 Agent 定义或系统检查。");
      if (kind == PlanNodeKind.SYSTEM_CHECK && budget != null)
        throw new IllegalArgumentException("系统检查步骤不使用 Agent 节点预算。");
      dependencies = List.copyOf(dependencies);
    }
  }
}
