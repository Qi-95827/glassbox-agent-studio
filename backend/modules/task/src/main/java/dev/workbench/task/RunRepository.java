package dev.workbench.task;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.workbench.agent.AgentToolGrant;
import dev.workbench.domain.*;
import dev.workbench.orchestration.Handoff;
import dev.workbench.orchestration.plan.PlanRepository;
import dev.workbench.skill.SkillAssignment;
import dev.workbench.skill.SkillId;
import dev.workbench.skill.SkillRef;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

/** 持久化边界：将领域枚举转换为稳定编码，保留已有查询接口格式。 */
@Repository
public class RunRepository {
  private final JdbcTemplate db;
  private final TransactionTemplate tx;
  private final ObjectMapper json;
  private final PlanRepository plans;
  private final HandoffValidator handoffValidator;

  public RunRepository(
      JdbcTemplate db,
      TransactionTemplate tx,
      ObjectMapper json,
      PlanRepository plans,
      HandoffValidator handoffValidator) {
    this.db = db;
    this.tx = tx;
    this.json = json;
    this.plans = plans;
    this.handoffValidator = handoffValidator;
  }

  public static String id() {
    return UUID.randomUUID().toString();
  }

  public static String now() {
    return Instant.now().toString();
  }

  public List<Map<String, Object>> tasks() {
    return db.queryForList(
        "SELECT t.*, (SELECT r.status FROM runs r WHERE r.task_id=t.id ORDER BY r.created_at DESC"
            + " LIMIT 1) AS status, (SELECT r.id FROM runs r WHERE r.task_id=t.id ORDER BY"
            + " r.created_at DESC LIMIT 1) AS run_id FROM tasks t ORDER BY t.created_at DESC");
  }

  public Map<String, Object> task(String id) {
    return db.queryForMap("SELECT * FROM tasks WHERE id=?", id);
  }

  public Map<String, Object> run(String id) {
    return db.queryForMap("SELECT * FROM runs WHERE id=?", id);
  }

  /** 将数据库记录转换为只读领域对象，避免执行层依赖列名和强制类型转换。 */
  public RunSnapshot runSnapshot(String id) {
    Map<String, Object> row = run(id);
    Object parent = row.get("PARENT_ID");
    return new RunSnapshot(
        UUID.fromString(row.get("ID").toString()),
        UUID.fromString(row.get("TASK_ID").toString()),
        parent == null ? null : UUID.fromString(parent.toString()),
        RunStatus.valueOf(row.get("STATUS").toString()),
        ((Number) row.get("ROUNDS")).intValue(),
        ((Number) row.get("TOOLS")).intValue(),
        ((Number) row.get("MAX_ROUNDS")).intValue(),
        ((Number) row.get("MAX_TOOLS")).intValue(),
        ((Number) row.get("MAX_PER_TOOL")).intValue(),
        row.get("MAX_REPORTED_TOKENS") == null
            ? null
            : ((Number) row.get("MAX_REPORTED_TOKENS")).longValue(),
        row.get("MAX_IDENTICAL_TOOLS") == null
            ? null
            : ((Number) row.get("MAX_IDENTICAL_TOOLS")).intValue(),
        ((Number) row.get("MAX_SECONDS")).intValue(),
        ((Number) row.get("ELAPSED_MS")).longValue(),
        row.get("MAX_AGENT_ROUNDS") == null
            ? null
            : ((Number) row.get("MAX_AGENT_ROUNDS")).intValue(),
        row.get("MAX_AGENT_TOOLS") == null
            ? null
            : ((Number) row.get("MAX_AGENT_TOOLS")).intValue());
  }

  public TaskDefinition taskDefinition(String id) {
    Map<String, Object> row = task(id);
    String fileScope = (String) row.get("FILE_SCOPE");
    List<String> fileScopes = readTextList(row.get("FILE_SCOPES_JSON"));
    if (fileScopes.isEmpty() && fileScope != null) fileScopes = List.of(fileScope);
    return new TaskDefinition(
        UUID.fromString(row.get("ID").toString()),
        row.get("GOAL").toString(),
        Scenario.fromCode(row.get("SCENARIO").toString()),
        ModelMode.fromCode(row.get("MODEL_MODE").toString()),
        (String) row.get("WORKSPACE_ID"),
        fileScope,
        Boolean.TRUE.equals(row.get("WRITE_ENABLED")),
        (String) row.get("BUG_ID"),
        Boolean.TRUE.equals(row.get("MEMORY_ENABLED")),
        readTextList(row.get("CONSTRAINTS_JSON")),
        readTextList(row.get("ACCEPTANCE_JSON")),
        TaskEntryKind.valueOf(row.get("ENTRY_KIND").toString()),
        fileScopes,
        roleBudgets(id),
        toolBudgets(id));
  }

  /** 读取任务角色策略；未知版本或角色不能当作未设置。 */
  public Map<AgentRole, RoleBudget> roleBudgets(String taskId) {
    var result = new java.util.EnumMap<AgentRole, RoleBudget>(AgentRole.class);
    db.query(
        "SELECT role_code,policy_version,max_model_calls,max_tool_calls FROM task_role_budgets"
            + " WHERE task_id=?",
        (org.springframework.jdbc.core.RowCallbackHandler)
            row -> {
              if (row.getInt("policy_version") != 1)
                throw new IllegalStateException("角色限额策略版本不受支持。");
              try {
                AgentRole role = AgentRole.valueOf(row.getString("role_code"));
                Object models = row.getObject("max_model_calls");
                Object tools = row.getObject("max_tool_calls");
                var budget =
                    new RoleBudget(
                        models == null ? null : ((Number) models).intValue(),
                        tools == null ? null : ((Number) tools).intValue());
                if (result.put(role, budget) != null) throw new IllegalStateException("角色限额重复。");
              } catch (IllegalArgumentException | NullPointerException e) {
                throw new IllegalStateException("角色限额策略无法读取。", e);
              }
            },
        taskId);
    return Map.copyOf(result);
  }

  /** 读取指定工具的策略；未知版本或类型不能当作未设置。 */
  public Map<ToolKind, Integer> toolBudgets(String taskId) {
    var result = new java.util.EnumMap<ToolKind, Integer>(ToolKind.class);
    db.query(
        "SELECT tool_kind,policy_version,max_calls FROM task_tool_budgets_v2 WHERE task_id=?",
        (org.springframework.jdbc.core.RowCallbackHandler)
            row -> {
              if (row.getInt("policy_version") != 1)
                throw new IllegalStateException("工具限额策略版本不受支持。");
              try {
                ToolKind kind = ToolKind.valueOf(row.getString("tool_kind"));
                int limit = row.getInt("max_calls");
                if (row.wasNull() || limit < 0 || limit > 20 || result.put(kind, limit) != null)
                  throw new IllegalStateException("工具限额策略无效。");
              } catch (IllegalArgumentException | NullPointerException e) {
                throw new IllegalStateException("工具限额策略无法读取。", e);
              }
            },
        taskId);
    return Map.copyOf(result);
  }

  /** 限额计算使用本运行已启动实例的角色，不能由模型自行声明。 */
  public AgentRole agentRole(String runId, String agentId) {
    String code =
        db.queryForObject(
            "SELECT role_code FROM agent_invocations WHERE run_id=? AND id=?",
            String.class,
            runId,
            agentId);
    try {
      return AgentRole.valueOf(code);
    } catch (IllegalArgumentException | NullPointerException e) {
      throw new IllegalStateException("Agent 角色不可确认，不能执行角色限额策略。", e);
    }
  }

  private List<String> readTextList(Object value) {
    try {
      String text =
          value instanceof java.sql.Clob clob
              ? clob.getSubString(1, (int) clob.length())
              : String.valueOf(value);
      return json.readValue(
          text, new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
    } catch (java.sql.SQLException | JsonProcessingException e) {
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
    return db.queryForList("SELECT * FROM events WHERE run_id=? ORDER BY id", id);
  }

  public List<Map<String, Object>> agents(String runId) {
    return db.queryForList(
        "SELECT * FROM agent_invocations WHERE run_id=? ORDER BY created_at", runId);
  }

  /** 记录已装配技能的不可变引用；不记录提示正文，也不授予工具权限。 */
  public void recordAgentSkills(String agentId, List<SkillRef> skills) {
    tx.executeWithoutResult(
        s -> {
          Integer count =
              db.queryForObject(
                  "SELECT COUNT(*) FROM agent_invocations WHERE id=? AND status='RUNNING'",
                  Integer.class,
                  agentId);
          if (count == null || count != 1) throw new IllegalStateException("Agent 未处于可装配技能的状态。");
          for (SkillRef skill : skills) {
            if (skill == null
                || skill.id() == null
                || skill.version() < 1
                || skill.contentSha256() == null
                || !skill.contentSha256().matches("[0-9a-f]{64}"))
              throw new IllegalArgumentException("技能引用无效。");
            db.update(
                "INSERT INTO agent_skills VALUES(?,?,?,?,?)",
                agentId,
                skill.id().name(),
                skill.version(),
                skill.contentSha256(),
                now());
          }
        });
  }

  public List<SkillAssignment> skills(String runId) {
    return db.query(
        "SELECT s.* FROM agent_skills s JOIN agent_invocations a ON a.id=s.agent_id WHERE"
            + " a.run_id=? ORDER BY a.created_at,s.skill_id",
        (r, i) ->
            new SkillAssignment(
                UUID.fromString(r.getString("agent_id")),
                SkillId.valueOf(r.getString("skill_id")),
                r.getInt("skill_version"),
                r.getString("content_sha256"),
                Instant.parse(r.getString("created_at"))),
        runId);
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
          Integer count =
              db.queryForObject(
                  "SELECT COUNT(*) FROM agent_invocations WHERE id=? AND status=?",
                  Integer.class,
                  agentId,
                  InvocationStatus.RUNNING.name());
          if (count == null || count != 1) throw new IllegalStateException("Agent 未处于可装配工具的状态。");
          db.update("INSERT INTO agent_tool_loads(agent_id,loaded_at) VALUES(?,?)", agentId, now());
          for (var tool : tools.entrySet()) {
            db.update(
                "INSERT INTO agent_tool_grants(agent_id,tool_kind,tool_version) VALUES(?,?,?)",
                agentId,
                tool.getKey().name(),
                tool.getValue());
          }
        });
  }

  /** 只查询本运行的装配标记；缺失标记与已登记空集合分开。 */
  public List<AgentToolGrant> agentToolGrants(String runId) {
    return db.query(
        "SELECT l.agent_id,l.loaded_at FROM agent_tool_loads l JOIN agent_invocations a"
            + " ON a.id=l.agent_id WHERE a.run_id=? ORDER BY a.created_at,a.id",
        (r, i) -> {
          String agentId = r.getString("agent_id");
          var tools =
              db.query(
                  "SELECT tool_kind,tool_version FROM agent_tool_grants WHERE agent_id=? ORDER BY"
                      + " tool_kind",
                  (row, n) ->
                      new AgentToolGrant.ToolVersion(
                          ToolKind.valueOf(row.getString("tool_kind")),
                          (Integer) row.getObject("tool_version")),
                  agentId);
          return new AgentToolGrant(
              UUID.fromString(agentId),
              Instant.parse(r.getString("loaded_at")),
              tools.stream().map(AgentToolGrant.ToolVersion::tool).toList(),
              tools);
        },
        runId);
  }

  public List<Map<String, Object>> tools(String runId) {
    return db.queryForList(
        "SELECT * FROM tool_invocations WHERE run_id=? ORDER BY created_at", runId);
  }

  public List<Map<String, Object>> models(String runId) {
    return db.queryForList(
        "SELECT * FROM model_invocations WHERE run_id=? ORDER BY created_at", runId);
  }

  public void startModel(String id, String runId, String agentId, ModelKind model) {
    String t = now();
    db.update(
        "INSERT INTO model_invocations VALUES(?,?,?,?,?,?,?,?,?,?,?)",
        id,
        runId,
        agentId,
        model.code(),
        InvocationStatus.RUNNING.name(),
        null,
        null,
        null,
        null,
        t,
        t);
  }

  public void finishModel(
      String id,
      InvocationStatus status,
      Integer input,
      Integer output,
      String error,
      long duration) {
    db.update(
        "UPDATE model_invocations SET"
            + " status=?,input_tokens=?,output_tokens=?,duration_ms=?,error_message=?,updated_at=?"
            + " WHERE id=? AND status='RUNNING'",
        status.name(),
        input,
        output,
        duration,
        error,
        now(),
        id);
  }

  public void startTool(String id, String runId, String agentId, String toolName) {
    startTool(id, runId, agentId, toolName, null);
  }

  public void startTool(String id, String runId, String agentId, String toolName, Integer version) {
    if (version != null && version < 1) throw new IllegalArgumentException("工具版本无效。");
    String t = now();
    db.update(
        "INSERT INTO"
            + " tool_invocations(id,run_id,agent_id,tool_name,status,result,error_message,duration_ms,created_at,updated_at,tool_version)"
            + " VALUES(?,?,?,?,?,?,?,?,?,?,?)",
        id,
        runId,
        agentId,
        toolName,
        InvocationStatus.RUNNING.name(),
        null,
        null,
        null,
        t,
        t,
        version);
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
          db.update(
              "INSERT INTO tool_attempt_fingerprints(run_id,tool_id,kind,fingerprint)"
                  + " VALUES(?,?,?,?)",
              runId,
              id,
              kind.name(),
              fingerprint);
        });
  }

  public void finishTool(
      String id, InvocationStatus status, String result, String error, long duration) {
    db.update(
        "UPDATE tool_invocations SET status=?,result=?,error_message=?,duration_ms=?,updated_at=?"
            + " WHERE id=? AND status='RUNNING'",
        status.name(),
        result,
        error,
        duration,
        now(),
        id);
  }

  public void handoff(HandoffDirection direction, Handoff payload) {
    handoffValidator.validate(direction, payload);
    try {
      String serialized = json.writeValueAsString(payload);
      try {
        db.update(
            "INSERT INTO handoffs VALUES(?,?,?,?,?,?)",
            payload.handoffId().toString(),
            payload.runId(),
            payload.agentId(),
            direction.name(),
            serialized,
            now());
      } catch (org.springframework.dao.DuplicateKeyException duplicate) {
        var existing =
            db.queryForMap(
                "SELECT direction,CAST(payload AS VARCHAR) AS payload FROM handoffs WHERE id=?",
                payload.handoffId().toString());
        if (!direction.name().equals(existing.get("DIRECTION"))
            || !serialized.equals(existing.get("PAYLOAD")))
          throw new IllegalStateException("交接 ID 已存在且内容不一致。");
      }
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("无法保存结构化交接记录。", e);
    }
  }

  public List<Map<String, Object>> handoffs(String runId) {
    return db
        .queryForList(
            "SELECT h.id,h.run_id,h.agent_id,h.direction,CAST(h.payload AS VARCHAR) AS"
                + " payload,h.created_at,r.receiver_agent_id,r.received_at FROM handoffs h LEFT"
                + " JOIN handoff_receipts r ON r.handoff_id=h.id AND r.run_id=h.run_id WHERE"
                + " h.run_id=? ORDER BY h.created_at,h.id",
            runId)
        .stream()
        .map(
            row -> {
              try {
                var result = new LinkedHashMap<>(row);
                result.put("PAYLOAD", json.readValue(row.get("PAYLOAD").toString(), Handoff.class));
                return (Map<String, Object>) result;
              } catch (JsonProcessingException e) {
                throw new IllegalStateException("结构化交接记录无法读取。", e);
              }
            })
        .toList();
  }

  public List<Long> toolEvidence(String runId) {
    return db.queryForList(
        "SELECT id FROM events WHERE run_id=? AND event_type='TOOL_FINISHED' ORDER BY id",
        Long.class,
        runId);
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
          db.update(
              "INSERT INTO handoff_receipts(handoff_id,run_id,receiver_agent_id,received_at)"
                  + " VALUES(?,?,?,?)",
              delegation.handoffId().toString(),
              runId,
              id,
              time);
          event(runId, EventType.HANDOFF_ACCEPTED, "已记录被委派 Agent 启动；不证明模型已读取委派内容。");
        });
  }

  /** 启动下游实例和确认交接为同一事务；确认不证明模型已读取输入。 */
  public void startAgentFromHandoff(
      String id, String runId, String goal, AgentRole role, UUID handoffId) {
    tx.executeWithoutResult(
        ignored -> {
          var row =
              db.queryForMap(
                  "SELECT direction,CAST(payload AS VARCHAR) AS payload FROM handoffs WHERE id=?"
                      + " AND run_id=?",
                  handoffId.toString(),
                  runId);
          Handoff handoff;
          try {
            handoff = json.readValue(row.get("PAYLOAD").toString(), Handoff.class);
          } catch (JsonProcessingException e) {
            throw new IllegalStateException("交接内容无法读取。", e);
          }
          if (!HandoffDirection.AGENT_TRANSFER.name().equals(row.get("DIRECTION"))
              || !handoffId.equals(handoff.handoffId())
              || !id.equals(handoff.recipientAgentId())
              || !runId.equals(handoff.runId())
              || !goal.equals(handoff.goal())
              || (role == AgentRole.CODER && handoff.receiver() != HandoffParty.CODING_AGENT)
              || (role == AgentRole.REVIEWER && handoff.receiver() != HandoffParty.REVIEW_AGENT)
              || (role != AgentRole.CODER && role != AgentRole.REVIEWER))
            throw new IllegalArgumentException("接收实例、角色或交接与运行不匹配。");
          handoffValidator.validate(HandoffDirection.AGENT_TRANSFER, handoff);
          Integer completed =
              db.queryForObject(
                  "SELECT COUNT(*) FROM agent_invocations WHERE id=? AND status=?",
                  Integer.class,
                  handoff.agentId(),
                  InvocationStatus.COMPLETED.name());
          if (completed == null || completed != 1)
            throw new IllegalStateException("上游 Agent 尚未完成，不能接收交接。");
          String time = now();
          startAgentRecord(id, runId, goal, role, time);
          db.update(
              "INSERT INTO handoff_receipts(handoff_id,run_id,receiver_agent_id,received_at)"
                  + " VALUES(?,?,?,?)",
              handoffId.toString(),
              runId,
              id,
              time);
          event(runId, EventType.HANDOFF_ACCEPTED, "已记录接收 Agent 启动；不证明模型已读取交接内容。");
        });
  }

  /** 由调用方提供事务，确保计划节点和 Agent 调用一起写入。 */
  private void startAgentRecord(String id, String runId, String goal, AgentRole role, String time) {
    plans.start(runId, id, role, time);
    db.update(
        "INSERT INTO"
            + " agent_invocations(id,run_id,role_name,task_goal,status,result,created_at,updated_at,role_code,role_version)"
            + " VALUES(?,?,?,?,?,?,?,?,?,?)",
        id,
        runId,
        role.displayName(),
        goal,
        InvocationStatus.RUNNING.name(),
        null,
        time,
        time,
        role.name(),
        role.version());
  }

  public void finishAgent(String id, InvocationStatus status, String result) {
    tx.executeWithoutResult(
        s -> {
          String t = now();
          plans.finish(id, status, t);
          int changed =
              db.update(
                  "UPDATE agent_invocations SET status=?,result=?,updated_at=? WHERE id=? AND"
                      + " status='RUNNING'",
                  status.name(),
                  result,
                  t,
                  id);
          if (changed != 1) throw new IllegalStateException("Agent 调用不存在或已经结束。");
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
    java.util.Objects.requireNonNull(completion, "运行终态不能为空");
    tx.executeWithoutResult(
        ignored -> {
          String runId = completion.runId().toString();
          String agentId = completion.agentId().toString();
          Integer reviewer =
              db.queryForObject(
                  "SELECT COUNT(*) FROM agent_invocations WHERE id=? AND run_id=? AND role_code=?"
                      + " AND status=?",
                  Integer.class,
                  agentId,
                  runId,
                  AgentRole.REVIEWER.name(),
                  InvocationStatus.RUNNING.name());
          if (reviewer == null || reviewer != 1)
            throw new IllegalStateException("当前运行没有可完成的审查 Agent。");
          finishAgentWithResult(agentId, completion.review(), completion.resultHandoff());
          event(runId, EventType.HANDOFF, "审查 Agent 已将审查结果返回协调者。");
          int changed =
              db.update(
                  "UPDATE runs SET"
                      + " status=?,reason=?,result=?,rounds=?,tools=?,elapsed_ms=?,stop_cause=NULL,updated_at=?"
                      + " WHERE id=? AND status=?",
                  completion.status().name(),
                  completion.reason(),
                  completion.result(),
                  completion.rounds(),
                  completion.tools(),
                  completion.elapsedMs(),
                  now(),
                  runId,
                  RunStatus.RUNNING.name());
          if (changed != 1) throw new IllegalStateException("运行已暂停或不在执行中，不能提交审查终态。");
          plans.settle(runId, now());
          if (completion.status() == RunStatus.BLOCKED)
            event(runId, EventType.BLOCKED, completion.reason());
          else event(runId, EventType.RUN_COMPLETED, "Agent 协作已结束；代码修改和测试需以实际证据为准。");
        });
  }

  /** 受控停止的 Agent、运行终态、事件和系统交接一起提交。 */
  public void finishStoppedRun(StopCompletion completion) {
    java.util.Objects.requireNonNull(completion, "停止记录不能为空");
    tx.executeWithoutResult(
        ignored -> {
          String runId = completion.runId().toString();
          RunStatus status = completion.cause().status();
          if (completion.agentId() != null)
            finishAgent(
                completion.agentId().toString(), InvocationStatus.fromRunStatus(status), null);
          update(
              runId,
              status,
              completion.reason(),
              null,
              completion.rounds(),
              completion.tools(),
              completion.elapsedMs(),
              completion.cause());
          EventType eventType =
              switch (status) {
                case INTERRUPTED -> EventType.INTERRUPTED;
                case FAILED -> EventType.FAILED;
                default -> EventType.fromStopStatus(status);
              };
          event(runId, eventType, completion.reason());
          if (completion.systemHandoff() != null)
            handoff(HandoffDirection.RESULT, completion.systemHandoff());
        });
  }

  public List<Map<String, Object>> checkpoints(String id) {
    return db
        .queryForList("SELECT * FROM checkpoints WHERE run_id=? ORDER BY created_at", id)
        .stream()
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
    return db.queryForMap("SELECT * FROM checkpoints WHERE id=?", id);
  }

  public List<Map<String, Object>> runs(String taskId) {
    return db.queryForList("SELECT * FROM runs WHERE task_id=? ORDER BY created_at DESC", taskId);
  }

  public boolean hasRecovery(String parent) {
    return Boolean.TRUE.equals(
        db.queryForObject(
            "SELECT COUNT(*) > 0 FROM runs WHERE parent_id=? AND NOT (recovery_dispatch IN (?,?)"
                + " AND status=?)",
            Boolean.class,
            parent,
            RecoveryDispatchStatus.REJECTED.name(),
            RecoveryDispatchStatus.PRECONDITION_FAILED.name(),
            RunStatus.FAILED.name()));
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
    db.update(
        "INSERT INTO"
            + " tasks(id,goal,scenario,created_at,model_mode,workspace_id,file_scope,write_enabled,bug_id,memory_enabled,constraints_json,acceptance_json,entry_kind,file_scopes_json)"
            + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        id,
        goal,
        scenario.code(),
        now(),
        modelMode.code(),
        workspaceId,
        fileScope,
        writeEnabled,
        bugId,
        memoryEnabled,
        writeTextList(constraints),
        writeTextList(acceptanceCriteria),
        entryKind.name(),
        writeTextList(fileScopes));
  }

  /** 首运行及计划与任务、创建事件同事务，避免持久化出孤立的半成品。 */
  public void createTaskAndFirstRun(
      TaskDefinition task, UUID runId, RunLimits limits, String message) {
    tx.executeWithoutResult(
        status -> {
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
            db.update(
                "INSERT INTO"
                    + " task_role_budgets(task_id,role_code,policy_version,max_model_calls,max_tool_calls)"
                    + " VALUES(?,?,?,?,?)",
                task.id().toString(),
                entry.getKey().name(),
                1,
                entry.getValue().maxModelCalls(),
                entry.getValue().maxToolCalls());
          }
          for (var entry : task.toolBudgets().entrySet()) {
            db.update(
                "INSERT INTO task_tool_budgets_v2(task_id,tool_kind,policy_version,max_calls)"
                    + " VALUES(?,?,?,?)",
                task.id().toString(),
                entry.getKey().name(),
                1,
                entry.getValue());
          }
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
              limits.maxAgentTools());
          event(runId.toString(), EventType.TASK_CREATED, message);
        });
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
              "从运行 " + original.id() + " 的检查点恢复；原历史保留。");
        });
  }

  /** 仅在线程池明确拒绝时调用；记录成功后父运行才允许再次申请恢复。 */
  public void rejectRecoveryBeforeDispatch(String runId) {
    tx.executeWithoutResult(
        status -> {
          String reason = "恢复请求未能进入执行队列，尚未启动 Agent，可重新申请恢复。";
          int changed =
              db.update(
                  "UPDATE runs SET status=?,reason=?,recovery_dispatch=?,updated_at=? WHERE id=?"
                      + " AND parent_id IS NOT NULL AND status=? AND recovery_dispatch=?",
                  RunStatus.FAILED.name(),
                  reason,
                  RecoveryDispatchStatus.REJECTED.name(),
                  now(),
                  runId,
                  RunStatus.PENDING.name(),
                  RecoveryDispatchStatus.UNKNOWN.name());
          if (changed != 1) throw new IllegalStateException("恢复派发状态无法安全确认。");
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
      int maxRounds,
      int maxTools,
      int maxSeconds,
      long elapsed) {
    createRun(
        id, taskId, parent, rounds, tools, maxRounds, maxTools, maxTools, maxSeconds, elapsed);
  }

  /** 仅当前置检查在任何 Agent、模型和工具调用之前失败时，才允许父运行重试。 */
  public void rejectRecoveryPrecondition(
      String runId, int rounds, int tools, long elapsed, String reason) {
    tx.executeWithoutResult(
        status -> {
          int changed =
              db.update(
                  "UPDATE runs SET"
                      + " status=?,reason=?,stop_cause=?,recovery_dispatch=?,rounds=?,tools=?,elapsed_ms=?,updated_at=?"
                      + " WHERE id=? AND parent_id IS NOT NULL AND status=? AND recovery_dispatch=?"
                      + " AND NOT EXISTS (SELECT 1 FROM agent_invocations WHERE run_id=?) AND NOT"
                      + " EXISTS (SELECT 1 FROM model_invocations WHERE run_id=?) AND NOT EXISTS"
                      + " (SELECT 1 FROM tool_invocations WHERE run_id=?) AND NOT EXISTS (SELECT 1"
                      + " FROM plan_nodes WHERE run_id=? AND status<>?)",
                  RunStatus.FAILED.name(),
                  reason,
                  dev.workbench.domain.StopCause.RECOVERY_PRECONDITION_FAILED.name(),
                  RecoveryDispatchStatus.PRECONDITION_FAILED.name(),
                  rounds,
                  tools,
                  elapsed,
                  now(),
                  runId,
                  RunStatus.RUNNING.name(),
                  RecoveryDispatchStatus.UNKNOWN.name(),
                  runId,
                  runId,
                  runId,
                  runId,
                  dev.workbench.orchestration.plan.PlanNodeStatus.PENDING.name());
          if (changed != 1) throw new IllegalStateException("无法确认恢复前置检查发生在任何 Agent 调用之前。");
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
      int maxRounds,
      int maxTools,
      int maxPerTool,
      int maxSeconds,
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
      int maxRounds,
      int maxTools,
      int maxPerTool,
      Long maxReportedTokens,
      int maxSeconds,
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
      int maxRounds,
      int maxTools,
      int maxPerTool,
      Long maxReportedTokens,
      int maxSeconds,
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
      int maxRounds,
      int maxTools,
      int maxPerTool,
      Long maxReportedTokens,
      Integer maxIdenticalTools,
      int maxSeconds,
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
      int maxRounds,
      int maxTools,
      int maxPerTool,
      Long maxReportedTokens,
      Integer maxIdenticalTools,
      int maxSeconds,
      long elapsed,
      boolean includeCoder,
      Integer maxAgentRounds,
      Integer maxAgentTools) {
    String now = now();
    tx.executeWithoutResult(
        s -> {
          db.update(
              "INSERT INTO"
                  + " runs(id,task_id,parent_id,status,reason,result,rounds,tools,max_rounds,max_tools,max_seconds,elapsed_ms,created_at,updated_at,max_per_tool,max_reported_tokens,max_identical_tools,max_agent_rounds,max_agent_tools)"
                  + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
              id,
              taskId,
              parent,
              RunStatus.PENDING.name(),
              null,
              null,
              rounds,
              tools,
              maxRounds,
              maxTools,
              maxSeconds,
              elapsed,
              now,
              now,
              maxPerTool,
              maxReportedTokens,
              maxIdenticalTools,
              maxAgentRounds,
              maxAgentTools);
          plans.initialize(id, includeCoder);
        });
  }

  /** 仅用于当前实例限额；已开始调用无论最终状态都计数。 */
  public int countAgentModelCalls(String agentId) {
    return db.queryForObject(
        "SELECT COUNT(*) FROM model_invocations WHERE agent_id=?", Integer.class, agentId);
  }

  public int countAgentToolCalls(String agentId) {
    return db.queryForObject(
        "SELECT COUNT(*) FROM tool_invocations WHERE agent_id=?", Integer.class, agentId);
  }

  /** 按记录顺序倒查当前运行及恢复祖先，遇到不同调用立即停止。 */
  public int consecutiveToolAttempts(String runId, ToolKind kind, String fingerprint, int limit) {
    int count = 0;
    var visited = new java.util.HashSet<String>();
    String current = runId;
    while (current != null && count < limit) {
      if (!visited.add(current)) throw new IllegalStateException("运行恢复关系存在循环。");
      var rows =
          db.queryForList(
              "SELECT kind,fingerprint FROM tool_attempt_fingerprints WHERE run_id=? ORDER BY id"
                  + " DESC LIMIT ?",
              current,
              limit - count);
      for (var row : rows) {
        if (!kind.name().equals(row.get("KIND")) || !fingerprint.equals(row.get("FINGERPRINT")))
          return count;
        if (++count >= limit) return count;
      }
      UUID parent = runSnapshot(current).parentId();
      current = parent == null ? null : parent.toString();
    }
    return count;
  }

  /** 已报告用量和未知用量次数；未知不能按零解释。 */
  public record ReportedTokenUsage(long knownTokens, long unknownCalls) {}

  public ReportedTokenUsage reportedTokens(String runId) {
    long known = 0, unknown = 0;
    var visited = new java.util.HashSet<String>();
    String current = runId;
    while (current != null) {
      if (!visited.add(current)) throw new IllegalStateException("运行恢复关系存在循环。");
      var row =
          db.queryForMap(
              "SELECT COALESCE(SUM(CAST(COALESCE(input_tokens,0) AS"
                  + " BIGINT)+CAST(COALESCE(output_tokens,0) AS BIGINT)),0) AS known, SUM(CASE WHEN"
                  + " input_tokens IS NULL OR output_tokens IS NULL THEN 1 ELSE 0 END) AS"
                  + " unknown_calls FROM model_invocations WHERE run_id=?",
              current);
      known += ((Number) row.get("KNOWN")).longValue();
      if (row.get("UNKNOWN_CALLS") != null)
        unknown += ((Number) row.get("UNKNOWN_CALLS")).longValue();
      UUID parent = runSnapshot(current).parentId();
      current = parent == null ? null : parent.toString();
    }
    return new ReportedTokenUsage(known, unknown);
  }

  /** 包含本运行和恢复祖先中的已启动调用，避免恢复时同种工具限额归零。 */
  public int countToolCalls(String runId, ToolKind kind) {
    int count = 0;
    var visited = new java.util.HashSet<String>();
    String current = runId;
    while (current != null) {
      if (!visited.add(current)) throw new IllegalStateException("运行恢复关系存在循环。");
      Integer found =
          db.queryForObject(
              "SELECT COUNT(*) FROM tool_invocations WHERE run_id=? AND tool_name=?",
              Integer.class,
              current,
              kind.recordName());
      count += found == null ? 0 : found;
      UUID parent = runSnapshot(current).parentId();
      current = parent == null ? null : parent.toString();
    }
    return count;
  }

  /** 检查点原因使用受控编码，便于稳定聚合且不暴露用户数据。 */
  public void checkpointSkipped(String runId, dev.workbench.domain.CheckpointSkipReason reason) {
    db.update(
        "INSERT INTO events(run_id,event_type,message,created_at,reason_code) VALUES(?,?,?,?,?)",
        runId,
        EventType.CHECKPOINT_SKIPPED.name(),
        reason.message(),
        now(),
        reason.name());
  }

  public void event(String runId, EventType type, String message) {
    db.update(
        "INSERT INTO events(run_id,event_type,message,created_at) VALUES(?,?,?,?)",
        runId,
        type.name(),
        message,
        now());
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
          int changed =
              db.update(
                  "UPDATE runs SET"
                      + " status=?,reason=?,result=?,rounds=?,tools=?,elapsed_ms=?,stop_cause=?,updated_at=?"
                      + " WHERE id=? AND status IN ('PENDING','RUNNING','PAUSED')",
                  status.name(),
                  reason,
                  result,
                  rounds,
                  tools,
                  elapsed,
                  stopCause == null ? null : stopCause.name(),
                  now(),
                  id);
          if (changed != 1) throw new IllegalStateException("运行不存在或已进入终态，不能再更新。");
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
          Long last =
              db.queryForObject("SELECT MAX(id) FROM events WHERE run_id=?", Long.class, runId);
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
          db.update(
              "INSERT INTO"
                  + " checkpoints(id,run_id,version,stage,rounds,tools,elapsed_ms,tool_result,event_id,created_at,checksum,engine)"
                  + " VALUES(?,?,?,?,?,?,?,?,?,?,?,'AGENTSCOPE')",
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
              sum);
        });
  }

  /** 已完成分析的引用与摘要；结果正文保留在原 Agent 调用中。 */
  public record AnalysisSource(UUID agentId, String result) {}

  /** 检查点复用的真实分析来源，不把祖先 Agent 当成本次调用。 */
  public record CheckpointAnalysisSource(UUID runId, UUID agentId, String result) {}

  public String analysisReference(String runId, String agentId) {
    var rows =
        db.queryForList(
            "SELECT result FROM agent_invocations WHERE id=? AND run_id=? AND role_name=? AND"
                + " status=?",
            agentId,
            runId,
            AgentRole.ANALYST.displayName(),
            InvocationStatus.COMPLETED.name());
    if (rows.size() != 1 || rows.getFirst().get("RESULT") == null)
      throw new IllegalArgumentException("分析来源不存在或尚未完成。");
    String result = rows.getFirst().get("RESULT").toString();
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
    String result =
        db.queryForObject(
            "SELECT result FROM agent_invocations WHERE id=? AND run_id=?",
            String.class,
            agentId,
            runId);
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
                        && n.status() == dev.workbench.orchestration.plan.PlanNodeStatus.REUSED
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
      Integer matches =
          db.queryForObject(
              "SELECT COUNT(*) FROM events WHERE id=? AND run_id=? AND"
                  + " event_type='CHECKPOINT_SAVED'",
              Integer.class,
              cp.get("EVENT_ID"),
              cp.get("RUN_ID"));
      return matches != null && matches == 1;
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
          List<Map<String, Object>> active =
              db.queryForList("SELECT id FROM runs WHERE status IN ('PENDING','RUNNING','PAUSED')");
          for (var row : active) {
            String id = row.get("ID").toString();
            db.update(
                "UPDATE runs SET status='INTERRUPTED',reason=?,updated_at=? WHERE id=?",
                "进程中断；未结束的调用结果未知。",
                now(),
                id);
            plans.settle(id, now());
            event(id, EventType.INTERRUPTED, "运行中断，可查看检查点并选择恢复。");
          }
          db.update(
              "UPDATE agent_invocations SET status='INTERRUPTED',updated_at=? WHERE"
                  + " status='RUNNING'",
              now());
          db.update(
              "UPDATE tool_invocations SET status='INTERRUPTED',error_message=?,updated_at=? WHERE"
                  + " status='RUNNING'",
              "进程中断，结果未知；耗时未完整记录。",
              now());
          db.update(
              "UPDATE model_invocations SET status='INTERRUPTED',error_message=?,updated_at=? WHERE"
                  + " status='RUNNING'",
              "进程中断，模型结果及用量未知。",
              now());
        });
  }

  public Map<String, Object> overview() {
    return db.queryForMap(
        "SELECT COUNT(*) AS total, COALESCE(SUM(CASE WHEN status='RUNNING' THEN 1 ELSE 0 END),0) AS"
            + " running, COALESCE(SUM(CASE WHEN status='COMPLETED' THEN 1 ELSE 0 END),0) AS"
            + " completed FROM runs");
  }
}
