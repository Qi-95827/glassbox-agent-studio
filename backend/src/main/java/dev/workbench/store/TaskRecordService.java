package dev.workbench.store;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.workbench.domain.*;
import dev.workbench.framework.ownership.OwnershipStamper;
import dev.workbench.persistence.entity.TaskEntity;
import dev.workbench.persistence.entity.TaskRoleBudgetEntity;
import dev.workbench.persistence.entity.TaskToolBudgetEntity;
import dev.workbench.persistence.repository.GitActionRepository;
import dev.workbench.persistence.repository.InvocationRepository;
import dev.workbench.persistence.repository.TaskData;
import dev.workbench.persistence.repository.TaskRepository;
import dev.workbench.persistence.repository.TaskRoleBudgetRepository;
import dev.workbench.persistence.repository.TaskToolBudgetRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedCaseInsensitiveMap;

/** 任务读取、定义、归档以及角色和工具预算读取。 */
@Service
public class TaskRecordService {
  private static final int BUDGET_POLICY_VERSION = 1;

  private final TaskRepository taskRows;
  private final TaskRoleBudgetRepository roleBudgetRows;
  private final TaskToolBudgetRepository toolBudgetRows;
  private final InvocationRepository invocationRows;
  private final GitActionRepository gitActions;
  private final OwnershipStamper ownership;
  private final ObjectMapper json;

  @org.springframework.beans.factory.annotation.Autowired
  public TaskRecordService(
      TaskRepository taskRows,
      TaskRoleBudgetRepository roleBudgetRows,
      TaskToolBudgetRepository toolBudgetRows,
      InvocationRepository invocationRows,
      GitActionRepository gitActions,
      OwnershipStamper ownership,
      ObjectMapper json) {
    this.taskRows = taskRows;
    this.roleBudgetRows = roleBudgetRows;
    this.toolBudgetRows = toolBudgetRows;
    this.invocationRows = invocationRows;
    this.gitActions = gitActions;
    this.ownership = ownership;
    this.json = json;
  }

  public TaskRecordService(
      TaskRepository taskRows,
      TaskRoleBudgetRepository roleBudgetRows,
      TaskToolBudgetRepository toolBudgetRows,
      InvocationRepository invocationRows,
      OwnershipStamper ownership,
      ObjectMapper json) {
    this(taskRows, roleBudgetRows, toolBudgetRows, invocationRows, null, ownership, json);
  }

  public List<Map<String, Object>> tasks() {
    var deliveredWorkspaces =
        gitActions == null
            ? java.util.Set.<String>of()
            : new java.util.HashSet<>(gitActions.findDeliveredWorkspaceIds());
    return taskRows.findAllWithLatestRun(RunStatus.PENDING.name()).stream()
        .map(
            task -> {
              var row = taskMap(task.task());
              String workspaceId = task.task().workspaceId();
              boolean hasWorkspace = workspaceId != null && !workspaceId.isBlank();
              boolean delivered = hasWorkspace && deliveredWorkspaces.contains(workspaceId);
              row.put("STATUS", task.status());
              row.put(
                  "TASK_STATUS",
                  TaskStatus.derive(task.status(), hasWorkspace, delivered).name());
              row.put("WORKSPACE_DELIVERED", delivered);
              row.put("RUN_ID", task.runId());
              row.put("PLAN_REVIEW_PENDING", task.planReviewPending());
              return row;
            })
        .toList();
  }

  public Map<String, Object> task(String id) {
    return taskMap(findTask(id));
  }

  public Map<String, Object> task(String id, String runStatus) {
    var task = findTask(id);
    var row = taskMap(task);
    String workspaceId = task.workspaceId();
    boolean hasWorkspace = workspaceId != null && !workspaceId.isBlank();
    boolean delivered =
        hasWorkspace
            && gitActions != null
            && gitActions.findDeliveredWorkspaceIds().contains(workspaceId);
    row.put("TASK_STATUS", TaskStatus.derive(runStatus, hasWorkspace, delivered).name());
    row.put("WORKSPACE_DELIVERED", delivered);
    return row;
  }

  private TaskData findTask(String id) {
    return taskRows.findTaskById(id).orElseThrow(() -> new EmptyResultDataAccessException(1));
  }

  private static Map<String, Object> taskMap(TaskData task) {
    Map<String, Object> row = new LinkedCaseInsensitiveMap<>();
    row.put("ID", task.id());
    row.put("GOAL", task.goal());
    row.put("SCENARIO", task.scenario());
    row.put("CREATED_AT", task.createdAt());
    row.put("ARCHIVED_AT", task.archivedAt());
    row.put("MODEL_MODE", task.modelMode());
    row.put("WORKSPACE_ID", task.workspaceId());
    row.put("FILE_SCOPE", task.fileScope());
    row.put("WRITE_ENABLED", task.writeEnabled());
    row.put("BUG_ID", task.bugId());
    row.put("MEMORY_ENABLED", task.memoryEnabled());
    row.put("CONSTRAINTS_JSON", task.constraintsJson());
    row.put("ACCEPTANCE_JSON", task.acceptanceJson());
    row.put("ENTRY_KIND", task.entryKind());
    row.put("FILE_SCOPES_JSON", task.fileScopesJson());
    row.put("VERSION", task.version());
    return row;
  }

  /** 归档只改变任务列表可见性，不删除运行、证据或 Git 产物。 */
  public void setTaskArchived(String taskId, boolean archived) {
    if (taskRows.setArchivedAt(taskId, archived ? RecordSupport.now() : null) != 1)
      throw new IllegalArgumentException("任务不存在。");
  }

  /** 当前任务版本；补充等信息变更会递增，提交方用它做乐观并发校验。 */
  public int taskVersion(String taskId) {
    return taskRows
        .findVersionById(taskId)
        .orElseThrow(() -> new EmptyResultDataAccessException(1));
  }

  public TaskDefinition taskDefinition(String id) {
    var task = findTask(id);
    String fileScope = task.fileScope();
    List<String> fileScopes = readTextList(task.fileScopesJson());
    if (fileScopes.isEmpty() && fileScope != null) fileScopes = List.of(fileScope);
    return new TaskDefinition(
        UUID.fromString(task.id()),
        task.goal(),
        Scenario.fromCode(task.scenario()),
        ModelMode.fromCode(task.modelMode()),
        task.workspaceId(),
        fileScope,
        task.writeEnabled(),
        task.bugId(),
        task.memoryEnabled(),
        readTextList(task.constraintsJson()),
        readTextList(task.acceptanceJson()),
        TaskEntryKind.valueOf(task.entryKind()),
        fileScopes,
        roleBudgets(id),
        toolBudgets(id));
  }

  /** 读取任务角色策略；未知版本或角色不能当作未设置。 */
  public Map<AgentRole, RoleBudget> roleBudgets(String taskId) {
    var result = new java.util.EnumMap<AgentRole, RoleBudget>(AgentRole.class);
    for (var row : taskRows.findRoleBudgets(taskId)) {
      if (!Integer.valueOf(BUDGET_POLICY_VERSION).equals(row.version()))
        throw new IllegalStateException("角色限额策略版本不受支持。");
      try {
        AgentRole role = AgentRole.valueOf(row.roleCode());
        if (result.put(role, new RoleBudget(row.maxModels(), row.maxTools())) != null)
          throw new IllegalStateException("角色限额重复。");
      } catch (IllegalArgumentException | NullPointerException e) {
        throw new IllegalStateException("角色限额策略无法读取。", e);
      }
    }
    return Map.copyOf(result);
  }

  /** 读取指定工具的策略；未知版本或类型不能当作未设置。 */
  public Map<ToolKind, Integer> toolBudgets(String taskId) {
    var result = new java.util.EnumMap<ToolKind, Integer>(ToolKind.class);
    for (var row : taskRows.findToolBudgets(taskId)) {
      if (!Integer.valueOf(BUDGET_POLICY_VERSION).equals(row.version()))
        throw new IllegalStateException("工具限额策略版本不受支持。");
      try {
        ToolKind kind = ToolKind.valueOf(row.toolKind());
        Integer limit = row.maxCalls();
        if (limit == null || limit < 0 || limit > 20 || result.put(kind, limit) != null)
          throw new IllegalStateException("工具限额策略无效。");
      } catch (IllegalArgumentException | NullPointerException e) {
        throw new IllegalStateException("工具限额策略无法读取。", e);
      }
    }
    return Map.copyOf(result);
  }

  /** 限额计算使用本运行已启动实例的角色，不能由模型自行声明。 */
  public AgentRole agentRole(String runId, String agentId) {
    String code = invocationRows.findRoleCode(runId, agentId);
    try {
      return AgentRole.valueOf(code);
    } catch (IllegalArgumentException | NullPointerException e) {
      throw new IllegalStateException("Agent 角色不可确认，不能执行角色限额策略。", e);
    }
  }

  private List<String> readTextList(String text) {
    try {
      return json.readValue(
          text, new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("任务结构化要求无法读取。", e);
    }
  }

  private String writeTextList(List<String> value) {
    try {
      return json.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("任务结构化要求无法保存。", e);
    }
  }

  private void createTask(
      String id,
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
    TaskEntity task = new TaskEntity();
    task.setId(id);
    task.setGoal(goal);
    task.setScenario(scenario.code());
    task.setCreatedAt(RecordSupport.now());
    task.setModelMode(modelMode.code());
    task.setWorkspaceId(workspaceId);
    task.setFileScope(fileScope);
    task.setWriteEnabled(writeEnabled);
    task.setBugId(bugId);
    task.setMemoryEnabled(memoryEnabled);
    task.setConstraintsJson(writeTextList(constraints));
    task.setAcceptanceJson(writeTextList(acceptanceCriteria));
    task.setEntryKind(entryKind.name());
    task.setFileScopesJson(writeTextList(fileScopes));
    taskRows.save(task);
    if (ownership != null) ownership.stamp(dev.workbench.ownership.OwnershipService.TASK, id);
  }

  /** 由门面在任务与首运行同一事务中调用。 */
  void createTaskRow(TaskDefinition task) {
    createTask(
        task.id().toString(),
        task.goal(),
        task.scenario(),
        task.modelMode(),
        task.workspaceId(),
        task.fileScope(),
        task.writeEnabled(),
        task.bugId(),
        task.memoryEnabled(),
        task.constraints(),
        task.acceptanceCriteria(),
        task.entryKind(),
        task.fileScopes());
    for (var entry : task.roleBudgets().entrySet()) {
      roleBudgetRows.save(
          new TaskRoleBudgetEntity(
              task.id().toString(),
              entry.getKey().name(),
              BUDGET_POLICY_VERSION,
              entry.getValue().maxModelCalls(),
              entry.getValue().maxToolCalls()));
    }
    for (var entry : task.toolBudgets().entrySet()) {
      toolBudgetRows.save(
          new TaskToolBudgetEntity(
              task.id().toString(),
              entry.getKey().name(),
              BUDGET_POLICY_VERSION,
              entry.getValue()));
    }
  }
}
