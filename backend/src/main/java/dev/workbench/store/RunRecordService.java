package dev.workbench.store;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.workbench.domain.*;
import dev.workbench.framework.agent.assembly.AgentAssemblySnapshot;
import dev.workbench.framework.agent.definition.AgentDefinitionRef;
import dev.workbench.framework.skill.SkillAssignment;
import dev.workbench.framework.skill.SkillRef;
import dev.workbench.framework.tool.AgentToolGrant;
import dev.workbench.framework.workflow.plan.WorkflowTemplate;
import dev.workbench.domain.Handoff;
import dev.workbench.persistence.entity.EventEntity;
import dev.workbench.persistence.entity.RunEntity;
import dev.workbench.persistence.entity.TaskEntity;
import dev.workbench.persistence.entity.TaskRoleBudgetEntity;
import dev.workbench.persistence.entity.TaskToolBudgetEntity;
import dev.workbench.persistence.repository.AgentConfigurationRepository;
import dev.workbench.persistence.repository.EventRepository;
import dev.workbench.persistence.repository.HandoffRepository;
import dev.workbench.persistence.repository.InvocationRepository;
import dev.workbench.persistence.repository.RunCheckpointRepository;
import dev.workbench.persistence.repository.RunData;
import dev.workbench.persistence.repository.RunRepository;
import dev.workbench.persistence.repository.TaskData;
import dev.workbench.persistence.repository.TaskRepository;
import dev.workbench.persistence.repository.TaskRoleBudgetRepository;
import dev.workbench.persistence.repository.TaskToolBudgetRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.LinkedCaseInsensitiveMap;
import dev.workbench.framework.workflow.plan.CandidateRunPlan;
import dev.workbench.store.HandoffValidator;
import dev.workbench.framework.workflow.plan.CandidateRunPlan;

/** 持久化边界：将领域枚举转换为稳定编码，保留已有查询接口格式。 */
@Service
public class RunRecordService implements dev.workbench.framework.control.RunControlStore, dev.workbench.framework.workflow.plan.PlanningRunReader {
  private static final int BUDGET_POLICY_VERSION = 1;
  private final AgentConfigurationRepository agentConfiguration;
  private final HandoffRepository handoffRows;
  private final RunCheckpointRepository checkpointRows;
  private final TransactionTemplate tx;
  private final ObjectMapper json;
  private final dev.workbench.framework.workflow.plan.PlanOperations plans;
  private final HandoffValidator handoffValidator;
  private final RunRepository runRows;
  private final EventRepository eventRows;
  private final InvocationRepository invocationRows;
  private final TaskRepository taskRows;
  private final TaskRoleBudgetRepository roleBudgetRows;
  private final TaskToolBudgetRepository toolBudgetRows;
  private final dev.workbench.framework.ownership.OwnershipStamper ownership;

  public RunRecordService(
      AgentConfigurationRepository agentConfiguration,
      HandoffRepository handoffRows,
      RunCheckpointRepository checkpointRows,
      TransactionTemplate tx,
      ObjectMapper json,
      dev.workbench.framework.workflow.plan.PlanOperations plans,
      HandoffValidator handoffValidator,
      RunRepository runRows,
      EventRepository eventRows,
      InvocationRepository invocationRows,
      TaskRepository taskRows,
      TaskRoleBudgetRepository roleBudgetRows,
      TaskToolBudgetRepository toolBudgetRows,
      dev.workbench.framework.ownership.OwnershipStamper ownership) {
    this.agentConfiguration = agentConfiguration;
    this.handoffRows = handoffRows;
    this.checkpointRows = checkpointRows;
    this.tx = tx;
    this.json = json;
    this.plans = plans;
    this.handoffValidator = handoffValidator;
    this.runRows = runRows;
    this.eventRows = eventRows;
    this.invocationRows = invocationRows;
    this.taskRows = taskRows;
    this.roleBudgetRows = roleBudgetRows;
    this.toolBudgetRows = toolBudgetRows;
    this.ownership = ownership;
  }

  public static String id() {
    return dev.workbench.domain.WorkbenchIds.newId();
  }

  public static String now() {
    return dev.workbench.domain.WorkbenchTime.now();
  }

  public List<Map<String, Object>> tasks() {
    return taskRows.findAllWithLatestRun(RunStatus.PENDING.name()).stream()
        .map(
            task -> {
              var row = taskMap(task.task());
              row.put("STATUS", task.status());
              row.put("RUN_ID", task.runId());
              row.put("PLAN_REVIEW_PENDING", task.planReviewPending());
              return row;
            })
        .toList();
  }

  public Map<String, Object> task(String id) {
    return taskMap(findTask(id));
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
    if (taskRows.setArchivedAt(taskId, archived ? now() : null) != 1)
      throw new IllegalArgumentException("任务不存在。");
  }

  public Map<String, Object> run(String id) {
    return findRun(id).apiFields();
  }

  public RunSnapshot runSnapshot(String id) {
    return findRun(id).snapshot();
  }

  private RunData findRun(String id) {
    return runRows.findRunById(id).orElseThrow(() -> new EmptyResultDataAccessException(1));
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

  /** 先验证摘要和事件引用，再转换检查点；拒绝不兼容或损坏的数据。 */
  public CheckpointSnapshot checkedCheckpoint(String id) {
    Map<String, Object> row = checkpoint(id);
    if (!validCheckpoint(row)) throw new IllegalArgumentException("检查点损坏、缺少关联事件或版本不兼容。");
    return new CheckpointSnapshot(
        UUID.fromString(row.get("ID").toString()),
        UUID.fromString(row.get("RUN_ID").toString()),
        CheckpointStage.valueOf(row.get("STAGE").toString()),
        ((Number) row.get("ROUNDS")).intValue(),
        ((Number) row.get("TOOLS")).intValue(),
        ((Number) row.get("ELAPSED_MS")).longValue(),
        (String) row.get("TOOL_RESULT"),
        ((Number) row.get("VERSION")).intValue());
  }

  public List<Map<String, Object>> events(String id) {
    return eventRows.findByRunId(id).stream().map(RunRecordService::eventMap).toList();
  }

  private static Map<String, Object> eventMap(EventEntity event) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("ID", event.getId());
    row.put("RUN_SEQ", event.getRunSeq());
    row.put("RUN_ID", event.getRunId());
    row.put("EVENT_TYPE", event.getEventType());
    row.put("MESSAGE", event.getMessage());
    row.put("CREATED_AT", event.getCreatedAt());
    row.put("REASON_CODE", event.getReasonCode());
    row.put("PLAN_VERSION_ID", event.getPlanVersionId());
    row.put("NODE_ID", event.getNodeId());
    row.put("ATTEMPT_ID", event.getAttemptId());
    row.put("AGENT_ID", event.getAgentId());
    row.put("CHECK_ID", event.getCheckId());
    row.put("MODEL_CALL_ID", event.getModelCallId());
    row.put("TOOL_CALL_ID", event.getToolCallId());
    return row;
  }

  public List<Map<String, Object>> agents(String runId) {
    return invocationRows.findAgentCalls(runId).stream()
        .map(InvocationRepository.AgentData::apiFields)
        .toList();
  }

  /** 读取真实实例所认领节点的定义；历史节点没有记录时返回空。 */
  public PlannedAgentDefinition plannedDefinitionForAgent(String runId, String agentId) {
    UUID instanceId = UUID.fromString(agentId);
    var matching =
        plans.find(runId).stream().filter(node -> instanceId.equals(node.agentId())).toList();
    if (matching.size() != 1) throw new IllegalStateException("Agent 与计划节点的归属不一致。");
    var node = matching.getFirst();
    if (node.definitionRef() != null && node.definitionSha256() == null) {
      throw new IllegalStateException("计划缺少 Agent 定义摘要，不能安全执行。");
    }
    return new PlannedAgentDefinition(node.definitionRef(), node.definitionSha256());
  }

  /** 历史计划引用和摘要可同时为空；不根据当前定义伪造历史摘要。 */
  public record PlannedAgentDefinition(AgentDefinitionRef reference, String sha256) {}

  /** 完整装配及兼容查询所需投影在同一事务内发布。 */
  public void recordAgentAssembly(String runId, String agentId, AgentAssemblySnapshot snapshot) {
    tx.executeWithoutResult(
        ignored -> {
          var selected = snapshot.components();
          var planned = plannedDefinitionForAgent(runId, agentId);
          if (planned.reference() != null
              && (!planned.reference().equals(selected.definition())
                  || !planned.sha256().equals(selected.definitionSha256())))
            throw new IllegalStateException("Agent 实际装配与计划指定的定义不一致。");
          if (agentRole(runId, agentId) != selected.authorization().profile())
            throw new IllegalStateException("Agent 装配权限与已认领实例不一致。");
          recordAgentDefinition(runId, agentId, selected.definition(), selected.definitionSha256());
          recordAgentSkills(agentId, selected.skills());
          recordAgentVersionedToolGrants(agentId, selected.tools());
          try {
            agentConfiguration.insertAssembly(agentId, now(), json.writeValueAsString(snapshot));
          } catch (JsonProcessingException e) {
            throw new IllegalStateException("实际装配快照无法保存。", e);
          }
        });
  }

  /** 历史没有快照的实例不会由现行配置推测补齐。 */
  public List<AgentAssemblySnapshot.Recorded> agentAssemblies(String runId) {
    return agentConfiguration.findAssemblies(runId).stream()
        .map(
            row -> {
              try {
                return new AgentAssemblySnapshot.Recorded(
                    UUID.fromString(row.agentId()),
                    Instant.parse(row.recordedAt()),
                    json.readValue(row.payload(), AgentAssemblySnapshot.class));
              } catch (JsonProcessingException e) {
                throw new IllegalStateException("实际装配快照版本不受支持或内容无法读取。", e);
              }
            })
        .toList();
  }

  /** 仅记录运行中实例实际解析的定义；历史空值不自动推断。 */
  public void recordAgentDefinition(
      String runId, String agentId, AgentDefinitionRef ref, String sha256) {
    java.util.Objects.requireNonNull(ref, "Agent 定义引用不能为空");
    if (sha256 == null || !sha256.matches("[0-9a-f]{64}"))
      throw new IllegalArgumentException("Agent 定义摘要无效。");
    if (!invocationRows.recordDefinition(runId, agentId, ref.id(), ref.version(), sha256))
      throw new IllegalStateException("Agent 不存在、已结束或定义已记录。");
  }

  /** 记录已装配技能的不可变引用；不记录提示正文，也不授予工具权限。 */
  public void recordAgentSkills(String agentId, List<SkillRef> skills) {
    tx.executeWithoutResult(
        s -> {
          if (!invocationRows.isRunningAgent(agentId))
            throw new IllegalStateException("Agent 未处于可装配技能的状态。");
          for (SkillRef skill : skills) {
            if (skill == null
                || skill.id() == null
                || skill.version() < 1
                || skill.contentSha256() == null
                || !skill.contentSha256().matches("[0-9a-f]{64}"))
              throw new IllegalArgumentException("技能引用无效。");
            agentConfiguration.insertSkill(agentId, skill, now());
          }
        });
  }

  public List<SkillAssignment> skills(String runId) {
    return agentConfiguration.findSkills(runId);
  }

  /** 注册集合和空集合标记在同一事务保存；失败时不应启动模型。 */
  public void recordAgentToolGrants(String agentId, java.util.Set<ToolKind> tools) {
    if (tools == null || tools.stream().anyMatch(java.util.Objects::isNull))
      throw new IllegalArgumentException("工具集合无效。");
    var versions = new java.util.EnumMap<ToolKind, Integer>(ToolKind.class);
    for (ToolKind tool : tools) versions.put(tool, null);
    saveAgentToolGrants(agentId, versions);
  }

  /** 新装配必须提供明确的服务端工具契约版本。 */
  public void recordAgentVersionedToolGrants(
      String agentId, java.util.Map<ToolKind, Integer> tools) {
    if (tools == null
        || tools.entrySet().stream()
            .anyMatch(e -> e.getKey() == null || e.getValue() == null || e.getValue() < 1))
      throw new IllegalArgumentException("工具版本无效。");
    saveAgentToolGrants(agentId, java.util.Map.copyOf(tools));
  }

  private void saveAgentToolGrants(String agentId, java.util.Map<ToolKind, Integer> tools) {
    tx.executeWithoutResult(
        status -> {
          if (!invocationRows.isRunningAgent(agentId))
            throw new IllegalStateException("Agent 未处于可装配工具的状态。");
          agentConfiguration.insertToolGrants(agentId, tools, now());
        });
  }

  /** 缺失标记与已登记空集合分开。 */
  public List<AgentToolGrant> agentToolGrants(String runId) {
    return agentConfiguration.findToolGrants(runId);
  }

  public List<Map<String, Object>> tools(String runId) {
    return invocationRows.findToolCalls(runId).stream()
        .map(InvocationRepository.ToolData::apiFields)
        .toList();
  }

  public List<Map<String, Object>> models(String runId) {
    return invocationRows.findModelCalls(runId).stream()
        .map(InvocationRepository.ModelData::apiFields)
        .toList();
  }

  public void startModel(String id, String runId, String agentId, ModelKind model) {
    invocationRows.insertModel(id, runId, agentId, model, now());
  }

  public void finishModel(
      String id,
      InvocationStatus status,
      Integer input,
      Integer output,
      String error,
      long duration) {
    invocationRows.finishModel(id, status, input, output, error, duration, now());
  }

  public void startTool(String id, String runId, String agentId, String toolName) {
    startTool(id, runId, agentId, toolName, null);
  }

  public void startTool(String id, String runId, String agentId, String toolName, Integer version) {
    if (version != null && version < 1) throw new IllegalArgumentException("工具版本无效。");
    invocationRows.insertTool(id, runId, agentId, toolName, version, now());
  }

  /** 已启用重复保护时，调用记录与指纹在同一事务中写入。 */
  public void startTool(
      String id, String runId, String agentId, ToolKind kind, String fingerprint) {
    startTool(id, runId, agentId, kind, null, fingerprint);
  }

  public void startTool(
      String id, String runId, String agentId, ToolKind kind, Integer version, String fingerprint) {
    if (fingerprint == null || !fingerprint.matches("[0-9a-f]{64}"))
      throw new IllegalArgumentException("工具调用指纹无效。");
    tx.executeWithoutResult(
        s -> {
          startTool(id, runId, agentId, kind.recordName(), version);
          invocationRows.insertToolFingerprint(runId, id, kind, fingerprint);
        });
  }

  public void finishTool(
      String id, InvocationStatus status, String result, String error, long duration) {
    finishTool(id, status, result, error, duration, null);
  }

  /** 只读工具的资源版本与调用终态一起保存；旧调用没有该证据时保持为空。 */
  public void finishTool(
      String id,
      InvocationStatus status,
      String result,
      String error,
      long duration,
      String resourceVersion) {
    invocationRows.finishTool(id, status, result, error, duration, resourceVersion, now());
  }

  public void handoff(HandoffDirection direction, Handoff payload) {
    tx.executeWithoutResult(
        status -> {
          handoffValidator.validate(direction, payload);
          if (!runRows.lockForUpdate(payload.runId()))
            throw new IllegalArgumentException("交接不属于指定任务运行。");
          try {
            String serialized = json.writeValueAsString(payload);
            String id = payload.handoffId().toString();
            var existing = handoffRows.findById(id).orElse(null);
            if (existing == null) {
              handoffRows.insert(
                  id, payload.runId(), payload.agentId(), direction, serialized, now());
            } else if (!direction.name().equals(existing.direction())
                || !serialized.equals(existing.json())) {
              throw new IllegalStateException("交接 ID 已存在且内容不一致。");
            }
          } catch (JsonProcessingException e) {
            throw new IllegalStateException("无法保存结构化交接记录。", e);
          }
        });
  }

  public List<Map<String, Object>> handoffs(String runId) {
    return handoffRows.findByRunId(runId).stream()
        .<Map<String, Object>>map(
            row -> {
              try {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("ID", row.id());
                result.put("RUN_ID", row.runId());
                result.put("AGENT_ID", row.agentId());
                result.put("DIRECTION", row.direction());
                result.put("PAYLOAD", json.readValue(row.payload(), Handoff.class));
                result.put("CREATED_AT", row.createdAt());
                result.put("RECEIVER_AGENT_ID", row.receiverAgentId());
                result.put("RECEIVED_AT", row.receivedAt());
                return result;
              } catch (JsonProcessingException e) {
                throw new IllegalStateException("结构化交接记录无法读取。", e);
              }
            })
        .toList();
  }

  public List<Long> toolEvidence(String runId) {
    return eventRows.findIdsByType(runId, EventType.TOOL_FINISHED);
  }

  public void startAgent(String id, String runId, String goal, AgentRole role) {
    tx.executeWithoutResult(ignored -> startAgentRecord(id, runId, goal, role, now()));
  }

  /** 首次委派、实例启动和接收记录共同提交，避免半成品运行。 */
  public void startAgentWithDelegation(String id, String runId, String goal, Handoff delegation) {
    tx.executeWithoutResult(
        ignored -> {
          if (delegation == null
              || delegation.handoffId() == null
              || !id.equals(delegation.agentId())
              || !id.equals(delegation.recipientAgentId())
              || !runId.equals(delegation.runId())
              || !goal.equals(delegation.goal())
              || delegation.source() != HandoffSource.COORDINATOR)
            throw new IllegalArgumentException("委派的接收实例、来源或运行不匹配。");
          String time = now();
          startAgentRecord(id, runId, goal, AgentRole.ANALYST, time);
          handoff(HandoffDirection.DELEGATION, delegation);
          handoffRows.insertReceipt(delegation.handoffId().toString(), runId, id, time);
          event(runId, EventType.HANDOFF_ACCEPTED, "已记录被委派 Agent 启动；不证明模型已读取委派内容。");
        });
  }

  /** 启动下游实例和确认交接为同一事务；确认不证明模型已读取输入。 */
  public void startAgentFromHandoff(
      String id, String runId, String goal, AgentRole role, UUID handoffId) {
    tx.executeWithoutResult(
        ignored -> {
          var row =
              handoffRows
                  .findById(handoffId.toString())
                  .filter(item -> runId.equals(item.runId()))
                  .orElseThrow(() -> new EmptyResultDataAccessException(1));
          Handoff handoff;
          try {
            handoff = json.readValue(row.json(), Handoff.class);
          } catch (JsonProcessingException e) {
            throw new IllegalStateException("交接内容无法读取。", e);
          }
          if (!HandoffDirection.AGENT_TRANSFER.name().equals(row.direction())
              || !handoffId.equals(handoff.handoffId())
              || !id.equals(handoff.recipientAgentId())
              || !runId.equals(handoff.runId())
              || !goal.equals(handoff.goal())
              || (role == AgentRole.CODER && handoff.receiver() != HandoffParty.CODING_AGENT)
              || (role == AgentRole.REVIEWER && handoff.receiver() != HandoffParty.REVIEW_AGENT)
              || (role != AgentRole.CODER && role != AgentRole.REVIEWER))
            throw new IllegalArgumentException("接收实例、角色或交接与运行不匹配。");
          handoffValidator.validate(HandoffDirection.AGENT_TRANSFER, handoff);
          if (!invocationRows.isCompletedAgent(handoff.agentId()))
            throw new IllegalStateException("上游 Agent 尚未完成，不能接收交接。");
          // 交接字段有效并不代表发送者属于当前接收节点的直接依赖。
          plans.requireDirectDependencySender(runId, role, UUID.fromString(handoff.agentId()));
          String time = now();
          startAgentRecord(id, runId, goal, role, time);
          handoffRows.insertReceipt(handoffId.toString(), runId, id, time);
          event(runId, EventType.HANDOFF_ACCEPTED, "已记录接收 Agent 启动；不证明模型已读取交接内容。");
        });
  }

  /** 由调用方提供事务，确保计划节点和 Agent 调用一起写入。 */
  private void startAgentRecord(String id, String runId, String goal, AgentRole role, String time) {
    plans.start(runId, id, role, time);
    insertAgent(id, runId, goal, role, time);
  }

  public void insertAgent(String id, String runId, String goal, AgentRole role, String time) {
    invocationRows.insertAgent(id, runId, role, goal, time);
  }

  public void finishAgent(String id, InvocationStatus status, String result) {
    tx.executeWithoutResult(
        transaction -> {
          String time = now();
          plans.finish(id, status, time);
          if (!invocationRows.finishAgent(id, status, result, time))
            throw new IllegalStateException("Agent 调用不存在或已经结束。");
        });
  }

  /** 成功结果交接与 Agent 结束状态共同提交；运行终态由协调者决定。 */
  public void finishAgentWithResult(String id, String result, Handoff resultHandoff) {
    if (resultHandoff == null
        || !id.equals(resultHandoff.agentId())
        || resultHandoff.status() != HandoffStatus.COMPLETED
        || !java.util.Objects.equals(result, resultHandoff.summary()))
      throw new IllegalArgumentException("Agent 结果与交接内容不一致。");
    tx.executeWithoutResult(
        ignored -> {
          finishAgent(id, InvocationStatus.COMPLETED, result);
          handoff(HandoffDirection.RESULT, resultHandoff);
        });
  }

  /** 审查完成、交接和整个运行终态一起提交，外部模型调用不在事务内。 */
  public void finishReviewedRun(ReviewCompletion completion) {
    java.util.Objects.requireNonNull(completion, "运行终态不能为。");
    tx.executeWithoutResult(
        ignored -> {
          String runId = completion.runId().toString();
          String agentId = completion.agentId().toString();
          if (!invocationRows.isRunningReviewer(runId, agentId))
            throw new IllegalStateException("当前运行没有可完成的审查 Agent?");
          finishAgentWithResult(agentId, completion.review(), completion.resultHandoff());
          event(runId, EventType.HANDOFF, "审查 Agent 已将审查结果返回协调者。");
          if (!runRows.finishReview(
              runId,
              completion.status(),
              completion.reason(),
              completion.result(),
              completion.rounds(),
              completion.tools(),
              completion.elapsedMs(),
              now())) throw new IllegalStateException("运行已暂停或不在执行中，不能提交审查终态。");
          plans.settle(runId, now());
          if (completion.status() == RunStatus.BLOCKED)
            event(runId, EventType.BLOCKED, completion.reason());
          else event(runId, EventType.RUN_COMPLETED, "Agent 协作已结束；代码修改和测试需以实际证据为准。");
        });
  }

  /** 受控停止与 Agent、运行终态、事件和系统交接一起提交。 */
  public void finishStoppedRun(StopCompletion completion) {
    java.util.Objects.requireNonNull(completion, "停止记录不能为空");
    tx.executeWithoutResult(
        ignored -> {
          String runId = completion.runId().toString();
          if (completion.agentId() != null)
            finishAgent(
                completion.agentId().toString(),
                InvocationStatus.fromRunStatus(completion.cause().status()),
                null);
          settleStoppedRun(
              runId,
              completion.cause(),
              completion.reason(),
              completion.rounds(),
              completion.tools(),
              completion.elapsedMs());
          if (completion.systemHandoff() != null)
            handoff(HandoffDirection.RESULT, completion.systemHandoff());
        });
  }

  public void settleStoppedRun(
      String runId, StopCause cause, String reason, int rounds, int tools, long elapsed) {
    RunStatus status = cause.status();
    update(runId, status, reason, null, rounds, tools, elapsed, cause);
    EventType eventType =
        switch (status) {
          case INTERRUPTED -> EventType.INTERRUPTED;
          case FAILED -> EventType.FAILED;
          case BLOCKED -> EventType.BLOCKED;
          default -> EventType.fromStopStatus(status);
        };
    event(runId, eventType, reason);
  }

  public List<Map<String, Object>> checkpoints(String id) {
    return checkpointRows.findByRunId(id).stream()
        .map(RunCheckpointRepository.CheckpointData::apiFields)
        .map(
            cp -> {
              var result = new LinkedHashMap<>(cp);
              result.put("VALID", validCheckpoint(cp));
              result.remove("TOOL_RESULT");
              result.remove("CHECKSUM");
              return (Map<String, Object>) result;
            })
        .toList();
  }

  public Map<String, Object> checkpoint(String id) {
    return checkpointRows
        .findById(id)
        .map(RunCheckpointRepository.CheckpointData::apiFields)
        .orElseThrow(() -> new EmptyResultDataAccessException(1));
  }

  public List<Map<String, Object>> runs(String taskId) {
    return runRows.findByTaskId(taskId).stream().map(RunData::apiFields).toList();
  }

  public boolean hasRecovery(String parent) {
    return runRows.hasRecovery(parent);
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
    task.setCreatedAt(now());
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

  /** 首运行及计划与任务、创建事件同事务，避免持久化出孤立的半成品。 */
  public void createTaskAndFirstRun(
      TaskDefinition task, UUID runId, RunLimits limits, String message) {
    createTaskAndFirstRun(task, runId, limits, message, null, false);
  }

  public void createTaskAndFirstRun(
      TaskDefinition task,
      UUID runId,
      RunLimits limits,
      String message,
      WorkflowTemplate workflow,
      boolean reviewRequired) {
    tx.executeWithoutResult(
        status -> {
          createTaskRow(task);
          createRun(
              runId.toString(),
              task.id().toString(),
              null,
              0,
              0,
              limits.maxRounds(),
              limits.maxTools(),
              limits.maxPerTool(),
              limits.maxReportedTokens(),
              limits.maxIdenticalTools(),
              limits.maxSeconds(),
              0,
              task.writeEnabled(),
              limits.maxAgentRounds(),
              limits.maxAgentTools(),
              workflow,
              null,
              reviewRequired);
          event(runId.toString(), EventType.TASK_CREATED, message);
        });
  }

  /** 已确认候选计划的任务、运行、计划、节点规格和创建事件一起提交。 */
  public void createTaskAndFirstRunFromPlan(
      TaskDefinition task,
      UUID runId,
      RunLimits limits,
      String message,
      dev.workbench.framework.workflow.plan.CandidateRunPlan plan,
      String planTime) {
    tx.executeWithoutResult(
        status -> {
          createTaskRow(task);
          createRun(
              runId.toString(),
              task.id().toString(),
              null,
              0,
              0,
              limits.maxRounds(),
              limits.maxTools(),
              limits.maxPerTool(),
              limits.maxReportedTokens(),
              limits.maxIdenticalTools(),
              limits.maxSeconds(),
              0,
              task.writeEnabled(),
              limits.maxAgentRounds(),
              limits.maxAgentTools(),
              null,
              plan,
              false);
          event(runId.toString(), EventType.TASK_CREATED, message);
          event(runId.toString(), EventType.PLAN_APPROVED, "用户确认候选计划并创建运行。");
        });
  }

  private void createTaskRow(TaskDefinition task) {
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

  /** 恢复子运行、计划和创建事件一起保存，预算采用父运行与快照的较大值。 */
  public void createRecoveryRun(UUID runId, RunSnapshot original, CheckpointSnapshot checkpoint) {
    if (!checkpoint.runId().equals(original.id()) || original.status() != RunStatus.INTERRUPTED)
      throw new IllegalArgumentException("检查点与中断运行不匹配。");
    tx.executeWithoutResult(
        status -> {
          createRun(
              runId.toString(),
              original.taskId().toString(),
              original.id().toString(),
              Math.max(original.rounds(), checkpoint.rounds()),
              Math.max(original.tools(), checkpoint.tools()),
              original.maxRounds(),
              original.maxTools(),
              original.maxPerTool(),
              original.maxReportedTokens(),
              original.maxIdenticalTools(),
              original.maxSeconds(),
              Math.max(original.elapsedMs(), checkpoint.elapsedMs()),
              false,
              original.maxAgentRounds(),
              original.maxAgentTools());
          event(
              runId.toString(),
              EventType.RECOVERY_STARTED,
              "从运。" + original.id() + " 的检查点恢复；原历史保留。");
        });
  }

  /** 调用方在源运行锁和恢复校验事务内创建副本。 */
  public void createWorkflowRecoveryRun(
      UUID id,
      RunSnapshot original,
      WorkflowTemplate template,
      boolean reviewRequired,
      int rounds,
      int tools,
      long elapsed) {
    createRun(
        id.toString(),
        original.taskId().toString(),
        original.id().toString(),
        Math.max(original.rounds(), rounds),
        Math.max(original.tools(), tools),
        original.maxRounds(),
        original.maxTools(),
        original.maxPerTool(),
        original.maxReportedTokens(),
        original.maxIdenticalTools(),
        original.maxSeconds(),
        Math.max(original.elapsedMs(), elapsed),
        false,
        original.maxAgentRounds(),
        original.maxAgentTools(),
        template,
        reviewRequired);
  }

  /** 仅在线程池明确拒绝时调用；记录成功后父运行才允许再次申请恢复。 */
  public void rejectRecoveryBeforeDispatch(String runId) {
    tx.executeWithoutResult(
        status -> {
          String reason = "恢复请求未能进入执行队列，尚未启动 Agent，可重新申请恢复。";
          if (!runRows.rejectBeforeDispatch(runId, reason, now()))
            throw new IllegalStateException("恢复派发状态无法安全确认。");
          plans.settle(runId, now());
          event(runId, EventType.FAILED, reason);
        });
  }

  public void createRun(
      String id,
      String taskId,
      String parent,
      int rounds,
      int tools,
      Integer maxRounds,
      Integer maxTools,
      Integer maxSeconds,
      long elapsed) {
    createRun(
        id, taskId, parent, rounds, tools, maxRounds, maxTools, maxTools, maxSeconds, elapsed);
  }

  /** 仅当前置检查在任何 Agent、模型和工具调用之前失败时，才允许父运行重试。 */
  public void rejectRecoveryPrecondition(
      String runId, int rounds, int tools, long elapsed, String reason) {
    tx.executeWithoutResult(
        status -> {
          if (!runRows.rejectRecoveryPrecondition(runId, rounds, tools, elapsed, reason, now()))
            throw new IllegalStateException("无法确认恢复前置检查发生在任何 Agent 调用之前。");
          plans.settle(runId, now());
          event(runId, EventType.FAILED, reason);
        });
  }

  /** 创建运行及固定计划；单工具上限随恢复运行传递。 */
  public void createRun(
      String id,
      String taskId,
      String parent,
      int rounds,
      int tools,
      Integer maxRounds,
      Integer maxTools,
      Integer maxPerTool,
      Integer maxSeconds,
      long elapsed) {
    createRun(
        id,
        taskId,
        parent,
        rounds,
        tools,
        maxRounds,
        maxTools,
        maxPerTool,
        null,
        maxSeconds,
        elapsed);
  }

  public void createRun(
      String id,
      String taskId,
      String parent,
      int rounds,
      int tools,
      Integer maxRounds,
      Integer maxTools,
      Integer maxPerTool,
      Long maxReportedTokens,
      Integer maxSeconds,
      long elapsed) {
    createRun(
        id,
        taskId,
        parent,
        rounds,
        tools,
        maxRounds,
        maxTools,
        maxPerTool,
        maxReportedTokens,
        maxSeconds,
        elapsed,
        false);
  }

  public void createRun(
      String id,
      String taskId,
      String parent,
      int rounds,
      int tools,
      Integer maxRounds,
      Integer maxTools,
      Integer maxPerTool,
      Long maxReportedTokens,
      Integer maxSeconds,
      long elapsed,
      boolean includeCoder) {
    createRun(
        id,
        taskId,
        parent,
        rounds,
        tools,
        maxRounds,
        maxTools,
        maxPerTool,
        maxReportedTokens,
        null,
        maxSeconds,
        elapsed,
        includeCoder);
  }

  public void createRun(
      String id,
      String taskId,
      String parent,
      int rounds,
      int tools,
      Integer maxRounds,
      Integer maxTools,
      Integer maxPerTool,
      Long maxReportedTokens,
      Integer maxIdenticalTools,
      Integer maxSeconds,
      long elapsed,
      boolean includeCoder) {
    createRun(
        id,
        taskId,
        parent,
        rounds,
        tools,
        maxRounds,
        maxTools,
        maxPerTool,
        maxReportedTokens,
        maxIdenticalTools,
        maxSeconds,
        elapsed,
        includeCoder,
        null,
        null);
  }

  public void createRun(
      String id,
      String taskId,
      String parent,
      int rounds,
      int tools,
      Integer maxRounds,
      Integer maxTools,
      Integer maxPerTool,
      Long maxReportedTokens,
      Integer maxIdenticalTools,
      Integer maxSeconds,
      long elapsed,
      boolean includeCoder,
      Integer maxAgentRounds,
      Integer maxAgentTools) {
    createRun(
        id,
        taskId,
        parent,
        rounds,
        tools,
        maxRounds,
        maxTools,
        maxPerTool,
        maxReportedTokens,
        maxIdenticalTools,
        maxSeconds,
        elapsed,
        includeCoder,
        maxAgentRounds,
        maxAgentTools,
        null,
        false);
  }

  private void createRun(
      String id,
      String taskId,
      String parent,
      int rounds,
      int tools,
      Integer maxRounds,
      Integer maxTools,
      Integer maxPerTool,
      Long maxReportedTokens,
      Integer maxIdenticalTools,
      Integer maxSeconds,
      long elapsed,
      boolean includeCoder,
      Integer maxAgentRounds,
      Integer maxAgentTools,
      WorkflowTemplate workflow,
      boolean reviewRequired) {
    createRun(
        id,
        taskId,
        parent,
        rounds,
        tools,
        maxRounds,
        maxTools,
        maxPerTool,
        maxReportedTokens,
        maxIdenticalTools,
        maxSeconds,
        elapsed,
        includeCoder,
        maxAgentRounds,
        maxAgentTools,
        workflow,
        null,
        reviewRequired);
  }

  private void createRun(
      String id,
      String taskId,
      String parent,
      int rounds,
      int tools,
      Integer maxRounds,
      Integer maxTools,
      Integer maxPerTool,
      Long maxReportedTokens,
      Integer maxIdenticalTools,
      Integer maxSeconds,
      long elapsed,
      boolean includeCoder,
      Integer maxAgentRounds,
      Integer maxAgentTools,
      WorkflowTemplate workflow,
      dev.workbench.framework.workflow.plan.CandidateRunPlan candidatePlan,
      boolean reviewRequired) {
    String now = now();
    tx.executeWithoutResult(
        s -> {
          RunEntity run = new RunEntity();
          run.setId(id);
          run.setTaskId(taskId);
          run.setParentId(parent);
          run.setStatus(RunStatus.PENDING.name());
          run.setReason(null);
          run.setResult(null);
          run.setRounds(rounds);
          run.setTools(tools);
          run.setMaxRounds(maxRounds);
          run.setMaxTools(maxTools);
          run.setMaxSeconds(maxSeconds);
          run.setElapsedMs(elapsed);
          run.setCreatedAt(now);
          run.setUpdatedAt(now);
          run.setMaxPerTool(maxPerTool);
          run.setMaxReportedTokens(maxReportedTokens);
          run.setMaxIdenticalTools(maxIdenticalTools);
          run.setMaxAgentRounds(maxAgentRounds);
          run.setMaxAgentTools(maxAgentTools);
          runRows.save(run);
          // 计划与节点外键引用运行，事件写入还要锁运行行；先落库再继续。
          runRows.flush();
          if (workflow == null) {
            if (candidatePlan == null) plans.initialize(id, includeCoder);
            else plans.initializeFromCandidate(id, candidatePlan, taskDefinition(taskId), now);
          } else
            plans.initializeWorkflow(id, workflow, taskDefinition(taskId), reviewRequired, now);
        });
  }

  /** 仅用于当前实例限额；已开始调用无论最终状态都计数。 */
  public int countAgentModelCalls(String agentId) {
    return invocationRows.countAgentModelCalls(agentId);
  }

  public int countAgentToolCalls(String agentId) {
    return invocationRows.countAgentToolCalls(agentId);
  }

  /** 累计节点的全部尝试，所有已记录启动的调用占用预算。 */
  public int countNodeModelCalls(String runId, UUID nodeId) {
    return invocationRows.countNodeModelCalls(runId, nodeId.toString());
  }

  public int countNodeToolCalls(String runId, UUID nodeId) {
    return invocationRows.countNodeToolCalls(runId, nodeId.toString());
  }

  /** 按记录顺序倒查当前运行及恢复祖先，遇到不同调用立即停止。 */
  public int consecutiveToolAttempts(String runId, ToolKind kind, String fingerprint, int limit) {
    int count = 0;
    var visited = new java.util.HashSet<String>();
    String current = runId;
    while (current != null && count < limit) {
      if (!visited.add(current)) throw new IllegalStateException("运行恢复关系存在循环。");
      var rows = invocationRows.findRecentFingerprints(current, limit - count);
      for (var row : rows) {
        if (!kind.name().equals(row.kind()) || !fingerprint.equals(row.fingerprint())) return count;
        if (++count >= limit) return count;
      }
      UUID parent = runSnapshot(current).parentId();
      current = parent == null ? null : parent.toString();
    }
    return count;
  }

  /** 已报告用量和未知用量次数；未知不能按零解释。 */

  public dev.workbench.framework.control.RunControlStore.ReportedTokenUsage reportedTokens(String runId) {
    long known = 0, unknown = 0;
    var visited = new java.util.HashSet<String>();
    String current = runId;
    while (current != null) {
      if (!visited.add(current)) throw new IllegalStateException("运行恢复关系存在循环。");
      var usage = invocationRows.summarizeTokens(current);
      known += usage.known();
      unknown += usage.unknownCalls();
      UUID parent = runSnapshot(current).parentId();
      current = parent == null ? null : parent.toString();
    }
    return new dev.workbench.framework.control.RunControlStore.ReportedTokenUsage(known, unknown);
  }

  /** 包含本运行和恢复祖先中的已启动调用，避免恢复时同种工具限额归零。 */
  public int countToolCalls(String runId, ToolKind kind) {
    int count = 0;
    var visited = new java.util.HashSet<String>();
    String current = runId;
    while (current != null) {
      if (!visited.add(current)) throw new IllegalStateException("运行恢复关系存在循环。");
      count += invocationRows.countToolCalls(current, kind.recordName());
      UUID parent = runSnapshot(current).parentId();
      current = parent == null ? null : parent.toString();
    }
    return count;
  }

  /** 检查点原因使用受控编码，便于稳定聚合且不暴露用户数据。 */
  public void checkpointSkipped(String runId, dev.workbench.domain.CheckpointSkipReason reason) {
    insertEvent(
        runId, EventType.CHECKPOINT_SKIPPED, reason.message(), EventRef.empty(), reason.name());
  }

  public void event(String runId, EventType type, String message) {
    event(runId, type, message, EventRef.empty());
  }

  /** 写入带结构化归属的事件；空关联与旧接口行为一致。 */
  public void event(String runId, EventType type, String message, EventRef ref) {
    insertEvent(runId, type, message, ref == null ? EventRef.empty() : ref, null);
  }

  /**
   * 事件写入与每运行序号分配在同一事务：先用 JPA 悲观锁锁定运行行，再取该运行当前最大序号。
   *
   * <p>同一运行的事件写入因此按提交顺序编号——持锁事务提交前，其他线程无法插入更晚序号。全局自增 ID 在跨运行并发时可能让晚提交事件落在旧游标之前；读取方一律使用 {@code
   * run_seq} 游标。
   */
  private void insertEvent(
      String runId, EventType type, String message, EventRef checked, String reasonCode) {
    tx.executeWithoutResult(
        status -> {
          if (!runRows.lockForUpdate(runId)) throw new IllegalArgumentException("运行不存在。");
          long next = eventRows.maxRunSeq(runId) + 1;
          eventRows.saveAndFlush(
              new EventEntity(
                  next,
                  runId,
                  type.name(),
                  message,
                  now(),
                  reasonCode,
                  text(checked.planVersionId()),
                  text(checked.nodeId()),
                  text(checked.attemptId()),
                  text(checked.agentId()),
                  text(checked.checkId()),
                  text(checked.modelCallId()),
                  text(checked.toolCallId())));
        });
  }

  /**
   * 按 Agent 实例查找已持久化的节点和尝试。
   *
   * <p>只返回实际存在的归属；兼容流程或尚未认领的实例保持空值，不从角色或计划顺序推断。
   */
  public EventRef eventRefForAgent(String runId, String agentId) {
    var origin = invocationRows.findEventOrigin(runId, agentId);
    var event = EventRef.agent(UUID.fromString(agentId));
    return origin == null
        ? event
        : event
            .withPlan(nullableUuid(origin.planId()))
            .withNode(nullableUuid(origin.nodeId()))
            .withAttempt(nullableUuid(origin.attemptId()));
  }

  private static String text(java.util.UUID value) {
    return value == null ? null : value.toString();
  }

  private static java.util.UUID nullableUuid(String value) {
    return value == null || value.isBlank() ? null : java.util.UUID.fromString(value);
  }

  public void update(
      String id,
      RunStatus status,
      String reason,
      String result,
      int rounds,
      int tools,
      long elapsed) {
    update(id, status, reason, result, rounds, tools, elapsed, null);
  }

  /** 状态和停止原因在同一事务保存；旧记录或不适用时原因保持未知。 */
  public void update(
      String id,
      RunStatus status,
      String reason,
      String result,
      int rounds,
      int tools,
      long elapsed,
      dev.workbench.domain.StopCause stopCause) {
    if (stopCause != null && stopCause.status() != status)
      throw new IllegalArgumentException("停止原因与运行状态不一致。");
    tx.executeWithoutResult(
        s -> {
          if (!runRows.updateActive(
              id, status, reason, result, rounds, tools, elapsed, stopCause, now()))
            throw new IllegalStateException("运行不存在或已进入终态，不能再更新。");
          if (!status.canCancel()) plans.settle(id, now());
        });
  }

  public void checkpoint(
      String runId, CheckpointStage stage, int rounds, int tools, long elapsed, String result) {
    if (stage == CheckpointStage.BEFORE_REVIEW && result == null)
      throw new IllegalArgumentException("审查前检查点需要分析来源。");
    int version = stage == CheckpointStage.BEFORE_REVIEW ? (result.contains("|") ? 5 : 4) : 3;
    writeCheckpoint(runId, stage, rounds, tools, elapsed, result, version);
  }

  /** 创建只读工作区的审查前检查点；摘要由工作区服务在分析边界取得。 */
  public void workspaceReviewCheckpoint(
      String runId,
      int rounds,
      int tools,
      long elapsed,
      dev.workbench.domain.WorkspaceReviewCheckpoint payload) {
    var task = taskDefinition(runSnapshot(runId).taskId().toString());
    if (task.workspaceId() == null
        || task.writeEnabled()
        || task.bugId() != null
        || task.memoryEnabled()
        || !task.workspaceId().equals(payload.workspaceId().toString()))
      throw new IllegalArgumentException("任务不满足只读工作区恢复条件。");
    checkpointAnalysisSource(runId, payload.analysisReference());
    try {
      writeCheckpoint(
          runId,
          CheckpointStage.BEFORE_REVIEW,
          rounds,
          tools,
          elapsed,
          json.writeValueAsString(payload),
          6);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("无法序列化工作区检查点。", e);
    }
  }

  /** 检查点 JSON 不含文件正文；格式不合法时拒绝恢复。 */
  public dev.workbench.domain.WorkspaceReviewCheckpoint workspaceReviewPayload(String value) {
    try {
      return json.readValue(value, dev.workbench.domain.WorkspaceReviewCheckpoint.class);
    } catch (JsonProcessingException | IllegalArgumentException e) {
      throw new IllegalArgumentException("工作区检查点内容无效。", e);
    }
  }

  private void writeCheckpoint(
      String runId,
      CheckpointStage stage,
      int rounds,
      int tools,
      long elapsed,
      String result,
      int version) {
    tx.executeWithoutResult(
        s -> {
          event(runId, EventType.CHECKPOINT_SAVED, "已保存内部状态：" + stage);
          Long last = eventRows.maxId(runId);
          String checkpointId = id();
          String created = now();
          String sum =
              checksum(
                  checkpointId,
                  runId,
                  String.valueOf(version),
                  "AGENTSCOPE",
                  stage.name(),
                  String.valueOf(rounds),
                  String.valueOf(tools),
                  String.valueOf(elapsed),
                  result,
                  String.valueOf(last),
                  created);
          checkpointRows.insert(
              new RunCheckpointRepository.CheckpointData(
                  checkpointId,
                  runId,
                  version,
                  stage.name(),
                  rounds,
                  tools,
                  elapsed,
                  result,
                  last,
                  created,
                  sum,
                  RunCheckpointRepository.AGENT_SCOPE_ENGINE));
        });
  }

  /** 已完成分析的引用与摘要；结果正文保留在原 Agent 调用中。 */
  public record AnalysisSource(UUID agentId, String result) {}

  /** 检查点复用的真实分析来源，不把祖先 Agent 当成本次调用。 */
  public record CheckpointAnalysisSource(UUID runId, UUID agentId, String result) {}

  public String analysisReference(String runId, String agentId) {
    String result = invocationRows.findCompletedAnalysis(runId, agentId);
    if (result == null) throw new IllegalArgumentException("分析来源不存在或尚未完成。");
    return UUID.fromString(agentId) + ":" + checksum(result);
  }

  /** 拒绝跨运行引用、损坏摘要和已经改变的分析结果。 */
  public AnalysisSource analysisSource(String runId, String reference) {
    if (reference == null || !reference.matches("[0-9a-fA-F-]{36}:[0-9a-f]{64}"))
      throw new IllegalArgumentException("分析检查点内容无效。");
    String agentId = reference.substring(0, 36);
    if (!MessageDigest.isEqual(
        analysisReference(runId, agentId).getBytes(StandardCharsets.US_ASCII),
        reference.getBytes(StandardCharsets.US_ASCII)))
      throw new IllegalArgumentException("分析来源摘要不匹配。");
    String result = invocationRows.readAgentResult(runId, agentId);
    return new AnalysisSource(UUID.fromString(agentId), result);
  }

  /** 解析旧版本运行引用或新版祖先引用，并核验运行链与复用节点。 */
  public CheckpointAnalysisSource checkpointAnalysisSource(
      String checkpointRunId, String reference) {
    if (reference == null) throw new IllegalArgumentException("分析检查点内容无效。");
    int separator = reference.indexOf('|');
    if (separator < 0) {
      var source = analysisSource(checkpointRunId, reference);
      return new CheckpointAnalysisSource(
          UUID.fromString(checkpointRunId), source.agentId(), source.result());
    }
    UUID sourceRun = UUID.fromString(reference.substring(0, separator));
    String originalReference = reference.substring(separator + 1);
    var current = runSnapshot(checkpointRunId);
    var currentTask = current.taskId();
    var ancestor = current.parentId();
    var visited = new java.util.HashSet<UUID>();
    visited.add(current.id());
    boolean found = false;
    while (ancestor != null) {
      if (!visited.add(ancestor) || visited.size() > 1000)
        throw new IllegalArgumentException("恢复运行链无效或过长。");
      var previous = runSnapshot(ancestor.toString());
      if (!previous.taskId().equals(currentTask))
        throw new IllegalArgumentException("恢复运行链跨越了任务边界。");
      if (previous.id().equals(sourceRun)) {
        found = true;
        break;
      }
      ancestor = previous.parentId();
    }
    if (!found) throw new IllegalArgumentException("分析来源不属于运行祖先。");
    var source = analysisSource(sourceRun.toString(), originalReference);
    var nodes = plans.find(checkpointRunId);
    boolean reused =
        nodes.stream()
            .anyMatch(
                n ->
                    n.role() == AgentRole.ANALYST
                        && n.status() == dev.workbench.framework.workflow.plan.PlanNodeStatus.REUSED
                        && source.agentId().equals(n.sourceAgentId()));
    if (!reused) throw new IllegalArgumentException("运行没有记录对该分析来源的复用。");
    return new CheckpointAnalysisSource(sourceRun, source.agentId(), source.result());
  }

  /** 保存引用前复验祖先关系，避免凭模型输出构造来源。 */
  public String ancestorAnalysisReference(
      String checkpointRunId, String sourceRunId, UUID sourceAgentId) {
    String reference = sourceRunId + "|" + analysisReference(sourceRunId, sourceAgentId.toString());
    checkpointAnalysisSource(checkpointRunId, reference);
    return reference;
  }

  public void reuseAnalysis(String runId, UUID sourceAgentId) {
    plans.reuseAnalysis(runId, sourceAgentId.toString(), now());
  }

  private static String checksum(String... parts) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      for (String part : parts) {
        byte[] bytes = (part == null ? "" : part).getBytes(StandardCharsets.UTF_8);
        digest.update(
            java.nio.ByteBuffer.allocate(4).putInt(part == null ? -1 : bytes.length).array());
        digest.update(bytes);
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  public boolean validCheckpoint(Map<String, Object> cp) {
    try {
      // 旧手写流程的快照没有框架上下文，不能当作 AgentScope 状态恢复。
      if (!"AGENTSCOPE".equals(cp.get("ENGINE"))) return false;
      if (cp.get("CHECKSUM") == null) return false;
      int version = ((Number) cp.get("VERSION")).intValue();
      CheckpointStage parsedStage = CheckpointStage.valueOf(cp.get("STAGE").toString());
      String stage = parsedStage.name();
      if (version == 3) {
        if (parsedStage != CheckpointStage.BEFORE_TOOL || cp.get("TOOL_RESULT") != null)
          return false;
      } else if (version == 4 || version == 5) {
        if (parsedStage != CheckpointStage.BEFORE_REVIEW) return false;
        String runId = cp.get("RUN_ID").toString();
        var task = taskDefinition(runSnapshot(runId).taskId().toString());
        if (task.workspaceId() != null || task.bugId() != null) return false;
        String reference = (String) cp.get("TOOL_RESULT");
        if (reference == null || (version == 5) != reference.contains("|")) return false;
        checkpointAnalysisSource(runId, reference);
      } else if (version == 6) {
        if (parsedStage != CheckpointStage.BEFORE_REVIEW) return false;
        String runId = cp.get("RUN_ID").toString();
        var task = taskDefinition(runSnapshot(runId).taskId().toString());
        var payload = workspaceReviewPayload((String) cp.get("TOOL_RESULT"));
        if (task.workspaceId() == null
            || task.writeEnabled()
            || task.bugId() != null
            || task.memoryEnabled()
            || !task.workspaceId().equals(payload.workspaceId().toString())) return false;
        checkpointAnalysisSource(runId, payload.analysisReference());
      } else return false;
      if (((Number) cp.get("ROUNDS")).intValue() < 0
          || ((Number) cp.get("TOOLS")).intValue() < 0
          || ((Number) cp.get("ELAPSED_MS")).longValue() < 0) return false;
      String expected =
          checksum(
              cp.get("ID").toString(),
              cp.get("RUN_ID").toString(),
              String.valueOf(version),
              "AGENTSCOPE",
              stage,
              cp.get("ROUNDS").toString(),
              cp.get("TOOLS").toString(),
              cp.get("ELAPSED_MS").toString(),
              (String) cp.get("TOOL_RESULT"),
              cp.get("EVENT_ID").toString(),
              cp.get("CREATED_AT").toString());
      if (!MessageDigest.isEqual(
          expected.getBytes(StandardCharsets.US_ASCII),
          cp.get("CHECKSUM").toString().getBytes(StandardCharsets.US_ASCII))) return false;
      return eventRows.exists(
          Long.parseLong(cp.get("EVENT_ID").toString()),
          cp.get("RUN_ID").toString(),
          EventType.CHECKPOINT_SAVED);
    } catch (NullPointerException
        | IllegalArgumentException
        | ClassCastException
        | org.springframework.dao.DataAccessException e) {
      // 字段缺失或编码非法属于不可恢复的快照，不应让整个列表查询失败。
      return false;
    }
  }

  public void interruptRunning() {
    tx.executeWithoutResult(
        s -> {
          for (var row : runRows.findForStartupInterruption()) {
            String id = row.id();
            runRows.interrupt(id, "进程中断；未结束的调用结果未知。", now());
            plans.settle(id, now());
            event(
                id,
                EventType.INTERRUPTED,
                row.planId() == null ? "运行中断，可查看支持的检查点。" : "动态工作流中断，已保留执行记录。可查询检查点的可用状态。");
          }
          invocationRows.interruptRunning(now());
        });
  }

  public Map<String, Object> overview() {
    return runRows.countByStatus().apiFields();
  }

  /** 运行概况按当前会话可读取的任务过滤，不返回全库状态计数。 */
  public Map<String, Object> overview(java.util.List<String> taskIds) {
    if (taskIds == null || taskIds.isEmpty())
      return new dev.workbench.persistence.repository.RunCounts(0L, 0L, 0L).apiFields();
    return runRows
        .countByStatusForTasks(taskIds, RunStatus.RUNNING.name(), RunStatus.COMPLETED.name())
        .apiFields();
  }
}
