package dev.workbench.framework.workflow.plan;

import dev.workbench.domain.AgentRole;
import dev.workbench.domain.InvocationStatus;
import dev.workbench.framework.agent.definition.AgentDefinitionRef;
import dev.workbench.framework.agent.definition.AgentDefinitionRegistry;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 固定顺序协作计划的持久化操作；写操作由调用方提供事务。 */
@Repository
public class PlanRepository {
  private final JdbcTemplate db;
  private final PlanFactory factory;
  private final AgentDefinitionRegistry definitions;

  @org.springframework.beans.factory.annotation.Autowired
  public PlanRepository(JdbcTemplate db, PlanFactory factory, AgentDefinitionRegistry definitions) {
    this.db = db;
    this.factory = factory;
    this.definitions = java.util.Objects.requireNonNull(definitions, "Agent 定义目录不能为空");
  }

  /** 兼容独立测试与旧调用，使用仅包含内置定义的受信目录。 */
  public PlanRepository(JdbcTemplate db, PlanFactory factory) {
    this(
        db,
        factory,
        new AgentDefinitionRegistry(
            new dev.workbench.agent.AgentRoleRegistry(),
            new dev.workbench.framework.skill.SkillRegistry(),
            List.of()));
  }

  /** 节点与依赖必须在同一事务写入；当前只创建服务端支持的固定计划。 */
  @org.springframework.transaction.annotation.Transactional
  public void initialize(String runId, boolean includeCoder) {
    var plan = factory.create(includeCoder);
    var workflow = plan.workflow();
    var ids = new java.util.LinkedHashMap<String, String>();
    for (var node : workflow.nodes()) ids.put(node.id(), UUID.randomUUID().toString());
    int sequence = 0;
    for (int i = 0; i < plan.nodes().size(); i++) {
      var node = plan.nodes().get(i);
      var workflowNode = workflow.nodes().get(i);
      AgentRole prerequisite =
          node.dependencies().isEmpty() ? null : node.dependencies().getFirst();
      var definition = workflowNode.agent();
      if (definitions.resolve(definition).permissionProfile() != node.role())
        throw new IllegalStateException("旧计划角色与 Agent 定义权限配置不一致。");
      String fingerprint = definitions.fingerprint(definition);
      db.update(
          "INSERT INTO plan_nodes(id,run_id,sequence_no,role_code,prerequisite_role,status,"
              + "definition_id,definition_version,definition_sha256) VALUES(?,?,?,?,?,?,?,?,?)",
          ids.get(workflowNode.id()),
          runId,
          ++sequence,
          node.role().name(),
          prerequisite == null ? null : prerequisite.name(),
          PlanNodeStatus.PENDING.name(),
          definition.id(),
          definition.version(),
          fingerprint);
    }
    for (var node : workflow.nodes()) {
      for (var prerequisite : node.dependencies()) {
        db.update(
            "INSERT INTO plan_node_dependencies(run_id,node_id,prerequisite_id) VALUES(?,?,?)",
            runId,
            ids.get(node.id()),
            ids.get(prerequisite));
      }
    }
  }

  /** 使用条件更新阻止重复启动或跳过尚未完成的前置步骤。 */
  public void start(String runId, String agentId, AgentRole role, String time) {
    var matching = find(runId).stream().filter(node -> node.role() == role).toList();
    if (matching.size() != 1) throw new IllegalStateException("旧角色必须唯一对应一个计划节点。");
    startNode(runId, matching.getFirst().id(), agentId, time);
  }

  /** 按节点标识认领；调用方应将其与实例和交接写入放在同一事务中。 */
  public void startNode(String runId, UUID nodeId, String agentId, String time) {
    java.util.Objects.requireNonNull(nodeId, "节点标识不能为空");
    var saved = find(runId);
    if (saved.isEmpty()) throw new IllegalStateException("运行没有已保存的计划。");
    var nodes =
        saved.stream()
            .map(
                node ->
                    new WorkflowGraph.Node(
                        node.id().toString(),
                        node.dependencyIds().stream().map(UUID::toString).toList()))
            .toList();
    var states = new java.util.LinkedHashMap<String, PlanNodeStatus>();
    for (var node : saved) states.put(node.id().toString(), node.status());
    var target =
        saved.stream()
            .filter(node -> node.id().equals(nodeId))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("计划中不存在该节点。"));
    if (!new WorkflowGraph(nodes).ready(states).contains(target.id().toString()))
      throw new IllegalStateException("步骤不存在、已启动或前置步骤尚未完成。");
    // 内存中的就绪计算不是锁；SQL 同时重新检查所有依赖和目标状态。
    int changed =
        db.update(
            "UPDATE plan_nodes SET status=?,agent_id=?,started_at=? WHERE id=? AND run_id=? AND"
                + " status=? AND (prerequisite_role IS NULL OR EXISTS (SELECT 1 FROM"
                + " plan_node_dependencies legacy JOIN plan_nodes p ON p.id=legacy.prerequisite_id"
                + " WHERE legacy.node_id=plan_nodes.id AND legacy.run_id=plan_nodes.run_id AND"
                + " p.run_id=plan_nodes.run_id AND p.role_code=plan_nodes.prerequisite_role AND"
                + " p.status IN (?,?))) AND NOT EXISTS (SELECT 1 FROM plan_node_dependencies d LEFT"
                + " JOIN plan_nodes p ON p.id=d.prerequisite_id WHERE d.node_id=plan_nodes.id AND"
                + " (d.run_id<>plan_nodes.run_id OR p.id IS NULL OR p.run_id<>plan_nodes.run_id OR"
                + " p.status NOT IN (?,?)))",
            PlanNodeStatus.RUNNING.name(),
            agentId,
            time,
            target.id().toString(),
            runId,
            PlanNodeStatus.PENDING.name(),
            PlanNodeStatus.COMPLETED.name(),
            PlanNodeStatus.REUSED.name(),
            PlanNodeStatus.COMPLETED.name(),
            PlanNodeStatus.REUSED.name());
    if (changed != 1) throw new IllegalStateException("步骤不存在、已启动或前置步骤尚未完成。");
  }

  /** 复用已校验的历史分析，不伪造本运行中的 Agent 调用。 */
  public void reuseAnalysis(String runId, String sourceAgentId, String time) {
    int changed =
        db.update(
            "UPDATE plan_nodes SET status=?,source_agent_id=?,finished_at=? WHERE run_id=? AND"
                + " role_code=? AND status=?",
            PlanNodeStatus.REUSED.name(),
            sourceAgentId,
            time,
            runId,
            AgentRole.ANALYST.name(),
            PlanNodeStatus.PENDING.name());
    if (changed != 1) throw new IllegalStateException("分析步骤不能复用。");
  }

  /** 调用与步骤共享终态；调用方应确保与 Agent 调用更新处于同一事务。 */
  public void finish(String agentId, InvocationStatus status, String time) {
    if (status == InvocationStatus.RUNNING) throw new IllegalArgumentException("调用尚未结束。");
    int changed =
        db.update(
            "UPDATE plan_nodes SET status=?,finished_at=? WHERE agent_id=? AND status=?",
            PlanNodeStatus.valueOf(status.name()).name(),
            time,
            agentId,
            PlanNodeStatus.RUNNING.name());
    if (changed != 1) throw new IllegalStateException("步骤不存在或已经结束。");
  }

  /** 终止运行时保留已结束步骤；在途步骤结果未知，未启动的步骤标记跳过。 */
  public void settle(String runId, String time) {
    db.update(
        "UPDATE plan_nodes SET status=?,finished_at=? WHERE run_id=? AND status=?",
        PlanNodeStatus.INTERRUPTED.name(),
        time,
        runId,
        PlanNodeStatus.RUNNING.name());
    db.update(
        "UPDATE plan_nodes SET status=?,finished_at=? WHERE run_id=? AND status=?",
        PlanNodeStatus.SKIPPED.name(),
        time,
        runId,
        PlanNodeStatus.PENDING.name());
  }

  /** 返回计划与真实保存的所有前置节点；异常关系不能被当作无依赖。 */
  public List<PlanNode> find(String runId) {
    List<PlanNode> rows =
        db.query(
            "SELECT * FROM plan_nodes WHERE run_id=? ORDER BY sequence_no",
            (row, index) -> {
              String prerequisite = row.getString("prerequisite_role");
              String agent = row.getString("agent_id");
              String sourceAgent = row.getString("source_agent_id");
              String started = row.getString("started_at");
              String finished = row.getString("finished_at");
              String definitionId = row.getString("definition_id");
              Integer definitionVersion = (Integer) row.getObject("definition_version");
              String fingerprint = row.getString("definition_sha256");
              if ((definitionId == null) != (definitionVersion == null))
                throw new IllegalStateException("计划节点的 Agent 定义引用不完整。");
              if ((fingerprint != null && !fingerprint.matches("[0-9a-f]{64}"))
                  || (definitionId == null && fingerprint != null))
                throw new IllegalStateException("计划节点的 Agent 定义摘要无效。");
              AgentDefinitionRef definition =
                  definitionId == null
                      ? null
                      : new AgentDefinitionRef(definitionId, definitionVersion);
              return new PlanNode(
                  UUID.fromString(row.getString("id")),
                  UUID.fromString(row.getString("run_id")),
                  row.getInt("sequence_no"),
                  AgentRole.valueOf(row.getString("role_code")),
                  prerequisite == null ? null : AgentRole.valueOf(prerequisite),
                  PlanNodeStatus.valueOf(row.getString("status")),
                  agent == null ? null : UUID.fromString(agent),
                  sourceAgent == null ? null : UUID.fromString(sourceAgent),
                  started == null ? null : Instant.parse(started),
                  finished == null ? null : Instant.parse(finished),
                  List.of(),
                  definition,
                  fingerprint);
            },
            runId);
    if (rows.isEmpty()) return List.of();
    var byId = new java.util.LinkedHashMap<UUID, PlanNode>();
    var byRole = new java.util.EnumMap<AgentRole, UUID>(AgentRole.class);
    var dependencies = new java.util.LinkedHashMap<UUID, java.util.List<UUID>>();
    for (var row : rows) {
      byId.put(row.id(), row);
      byRole.put(row.role(), row.id());
      dependencies.put(row.id(), new java.util.ArrayList<>());
    }
    var edges =
        db.query(
            "SELECT d.run_id,d.node_id,d.prerequisite_id FROM plan_node_dependencies d JOIN"
                + " plan_nodes n ON n.id=d.node_id WHERE n.run_id=? ORDER BY"
                + " n.sequence_no,d.prerequisite_id",
            (r, i) ->
                new Dependency(
                    r.getString("run_id"),
                    UUID.fromString(r.getString("node_id")),
                    UUID.fromString(r.getString("prerequisite_id"))),
            runId);
    for (var edge : edges) {
      if (!runId.equals(edge.runId())
          || !byId.containsKey(edge.nodeId())
          || !byId.containsKey(edge.prerequisiteId()))
        throw new IllegalStateException("计划依赖不存在或跨越运行边界。");
      dependencies.get(edge.nodeId()).add(edge.prerequisiteId());
    }
    var result = new java.util.ArrayList<PlanNode>();
    for (var row : rows) {
      var ids = dependencies.get(row.id());
      if (row.prerequisite() != null
          && (byRole.get(row.prerequisite()) == null
              || !ids.contains(byRole.get(row.prerequisite()))))
        throw new IllegalStateException("历史计划的前置依赖缺失或不一致。");
      result.add(
          new PlanNode(
              row.id(),
              row.runId(),
              row.sequence(),
              row.role(),
              row.prerequisite(),
              row.status(),
              row.agentId(),
              row.sourceAgentId(),
              row.startedAt(),
              row.finishedAt(),
              ids,
              row.definitionRef(),
              row.definitionSha256()));
    }
    new WorkflowGraph(
        result.stream()
            .map(
                node ->
                    new WorkflowGraph.Node(
                        node.id().toString(),
                        node.dependencyIds().stream().map(UUID::toString).toList()))
            .toList());
    return List.copyOf(result);
  }

  private record Dependency(String runId, UUID nodeId, UUID prerequisiteId) {}
}
