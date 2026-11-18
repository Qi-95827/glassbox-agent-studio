package dev.workbench.framework.workflow.plan;

import dev.workbench.domain.AgentRole;
import dev.workbench.domain.InvocationStatus;
import dev.workbench.domain.TaskDefinition;
import dev.workbench.domain.ToolKind;
import dev.workbench.framework.agent.assembly.AgentAssemblySnapshot;
import dev.workbench.framework.agent.definition.AgentDefinition;
import dev.workbench.framework.agent.definition.AgentDefinitionRef;
import dev.workbench.framework.agent.definition.AgentDefinitionRegistry;
import dev.workbench.framework.workflow.check.SystemCheckRef;
import dev.workbench.framework.workflow.check.SystemCheckRegistry;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 保存兼容计划与版本化工作流，节点状态使用同一套条件更新。 */
@Repository
public class PlanRepository {
  private final JdbcTemplate db;
  private final PlanFactory factory;
  private final AgentDefinitionRegistry definitions;
  private final SystemCheckRegistry checks;

  @org.springframework.beans.factory.annotation.Autowired
  public PlanRepository(
      JdbcTemplate db,
      PlanFactory factory,
      AgentDefinitionRegistry definitions,
      SystemCheckRegistry checks) {
    this.db = db;
    this.factory = factory;
    this.definitions = java.util.Objects.requireNonNull(definitions, "Agent 定义目录不能为空");
    this.checks = java.util.Objects.requireNonNull(checks, "系统检查目录不能为空");
  }

  /** 兼容独立测试与旧调用，使用仅包含内置定义的受信目录。 */
  public PlanRepository(JdbcTemplate db, PlanFactory factory) {
    this(
        db,
        factory,
        new AgentDefinitionRegistry(
            new dev.workbench.agent.AgentRoleRegistry(),
            new dev.workbench.framework.skill.SkillRegistry(),
            List.of()),
        new SystemCheckRegistry(
            List.of(new dev.workbench.framework.workflow.check.AuthorizedReadEvidenceCheck())));
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
    saveEdges(runId, workflow, ids);
  }

  /** 由创建运行事务调用，先保存计划和节点，再保存引用这些节点的边。 */
  @org.springframework.transaction.annotation.Transactional
  public void initializeWorkflow(
      String runId,
      WorkflowTemplate template,
      TaskDefinition task,
      boolean reviewRequired,
      String time) {
    String versionId = UUID.randomUUID().toString();
    var scope = template.scope();
    String authorization = scope.authorizationDigest(task);
    boolean hasSystemCheck =
        template.steps().stream().anyMatch(step -> step.kind() == PlanNodeKind.SYSTEM_CHECK);
    if (hasSystemCheck && scope == WorkflowTemplate.Scope.TEXT_ONLY)
      throw new IllegalArgumentException("系统检查节点只能用于工作区范围的模板。");
    // 先核对全部受信引用，再写入任何计划记录，避免留下半成品计划。
    boolean hasWorkspaceReader = false;
    for (var step : template.steps()) {
      if (step.kind() == PlanNodeKind.SYSTEM_CHECK) {
        checks.resolve(step.check());
        continue;
      }
      var definition = definitions.resolve(step.agent());
      validateDefinition(scope, definition);
      hasWorkspaceReader |= !definition.tools().isEmpty();
    }
    var workflow =
        new WorkflowDefinition(
            template.steps().stream()
                .map(
                    step ->
                        new WorkflowDefinition.Node(
                            step.key(), step.agent(), step.check(), step.dependencies()))
                .toList());
    var ids = new java.util.LinkedHashMap<String, String>();
    db.update(
        "INSERT INTO"
            + " workflow_plans(id,run_id,plan_version,template_id,template_version,review_required,approved_at,scope_type,authorization_sha256,max_attempts)"
            + " VALUES (?,?,1,?,?,?,?,?,?,?)",
        versionId,
        runId,
        template.id(),
        template.version(),
        reviewRequired,
        reviewRequired ? null : time,
        scope.name(),
        authorization,
        template.maxAttempts());
    int sequence = 0;
    for (var step : template.steps()) {
      String nodeId = UUID.randomUUID().toString();
      ids.put(step.key(), nodeId);
      if (step.kind() == PlanNodeKind.SYSTEM_CHECK) {
        // 检查只读取已结算的前置结果，不能使用 Agent 定义、角色或工具权限。
        db.update(
            "INSERT INTO"
                + " plan_nodes(id,run_id,sequence_no,status,plan_version_id,node_key,node_label,"
                + "phase_key,workspace_read,workspace_write,node_kind,check_id,check_version)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
            nodeId,
            runId,
            ++sequence,
            PlanNodeStatus.PENDING.name(),
            versionId,
            step.key(),
            step.label(),
            step.phase(),
            false,
            false,
            PlanNodeKind.SYSTEM_CHECK.name(),
            step.check().id(),
            step.check().version());
        continue;
      }
      var definition = definitions.resolve(step.agent());
      boolean workspaceRead = !definition.tools().isEmpty();
      boolean workspaceWrite = definition.tools().contains(ToolKind.WORKSPACE_WRITE);
      db.update(
          "INSERT INTO plan_nodes(id,run_id,sequence_no,status,definition_id,definition_version,"
              + "definition_sha256,plan_version_id,node_key,node_label,phase_key,permission_role,workspace_read,workspace_write,max_node_model_calls,max_node_tool_calls,node_kind)"
              + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
          nodeId,
          runId,
          ++sequence,
          PlanNodeStatus.PENDING.name(),
          step.agent().id(),
          step.agent().version(),
          definitions.fingerprint(step.agent()),
          versionId,
          step.key(),
          step.label(),
          step.phase(),
          definition.permissionProfile().name(),
          workspaceRead,
          workspaceWrite,
          step.budget() == null ? null : step.budget().maxModelCalls(),
          step.budget() == null ? null : step.budget().maxToolCalls(),
          PlanNodeKind.AGENT.name());
    }
    if (scope.hasWorkspace() && !hasWorkspaceReader)
      throw new IllegalArgumentException("工作区模板缺少读取节点。");
    saveEdges(runId, workflow, ids);
    var nodes = find(runId);
    if (scope.allowsWrite()) requireIndependentReview(nodes);
    db.update(
        "UPDATE workflow_plans SET graph_sha256=? WHERE id=?",
        graphFingerprint(nodes, scope, authorization, template.maxAttempts()),
        versionId);
  }

  private void saveEdges(
      String runId, WorkflowDefinition workflow, java.util.Map<String, String> ids) {
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

  public WorkflowPlan workflowPlan(String runId) {
    return db
        .query(
            "SELECT * FROM workflow_plans WHERE run_id=?",
            (row, index) -> {
              String approved = row.getString("approved_at");
              String checkpoint = row.getString("source_checkpoint_id");
              return new WorkflowPlan(
                  UUID.fromString(row.getString("id")),
                  row.getInt("plan_version"),
                  row.getString("template_id"),
                  row.getInt("template_version"),
                  row.getBoolean("review_required"),
                  approved == null ? null : Instant.parse(approved),
                  row.getString("graph_sha256"),
                  checkpoint == null ? null : UUID.fromString(checkpoint),
                  WorkflowTemplate.Scope.valueOf(row.getString("scope_type")),
                  row.getString("authorization_sha256"),
                  row.getInt("max_attempts"));
            },
            runId)
        .stream()
        .findFirst()
        .orElse(null);
  }

  /** 固定计划的摘要不含状态与时间，用于发现图或装配引用发生意外变化。 */
  public List<PlanNode> checkedWorkflowNodes(String runId, WorkflowPlan plan) {
    var nodes = find(runId);
    if (nodes.isEmpty()
        || nodes.stream().anyMatch(node -> !plan.id().equals(node.planVersionId()))
        || !graphFingerprint(nodes, plan.scope(), plan.authorizationSha256(), plan.maxAttempts())
            .equals(plan.graphSha256())) throw new IllegalStateException("保存的工作流图与计划版本摘要不一致。");
    for (var node : nodes) {
      if (node.systemCheck()) {
        checks.resolve(node.checkRef());
        if (node.workspaceRead() || node.workspaceWrite())
          throw new IllegalStateException("系统检查节点不能声明工作区工具权限。");
        continue;
      }
      var definition = definitions.resolve(node.definitionRef());
      validateDefinition(plan.scope(), definition);
      if (definition.permissionProfile() != node.role()
          || node.workspaceRead() != !definition.tools().isEmpty()
          || node.workspaceWrite() != definition.tools().contains(ToolKind.WORKSPACE_WRITE)
          || !definitions.fingerprint(node.definitionRef()).equals(node.definitionSha256()))
        throw new IllegalStateException("工作流引用的定义版本或只读权限与保存的计划不一致。");
    }
    return nodes;
  }

  private static void validateDefinition(WorkflowTemplate.Scope scope, AgentDefinition definition) {
    boolean profileAllowed =
        definition.permissionProfile() == AgentRole.ANALYST
            || (scope.allowsWrite()
                && (definition.permissionProfile() == AgentRole.CODER
                    || definition.permissionProfile() == AgentRole.REVIEWER));
    if (!profileAllowed || definition.tools().stream().anyMatch(tool -> !scope.permits(tool)))
      throw new IllegalArgumentException("工作流的 Agent 定义超出模板的权限上限。");
  }

  private static void requireIndependentReview(List<PlanNode> nodes) {
    var writers =
        nodes.stream().filter(node -> !node.systemCheck() && node.workspaceWrite()).toList();
    if (writers.isEmpty()
        || writers.stream()
            .anyMatch(
                writer ->
                    nodes.stream()
                        .noneMatch(
                            reviewer ->
                                !reviewer.systemCheck()
                                    && reviewer.role() == AgentRole.REVIEWER
                                    && !reviewer.workspaceRead()
                                    && reviewer.dependencyIds().contains(writer.id()))))
      throw new IllegalArgumentException("每个写入节点都必须有独立的直接审查节点。");
  }

  /** 恢复副本留存原计划来源；已核验结果只关联历史实例。 */
  public void attachRecovery(
      String runId, UUID checkpointId, java.util.Map<String, UUID> reused, String time) {
    if (db.update(
            "UPDATE workflow_plans SET source_checkpoint_id=?,approved_at=COALESCE(approved_at,?)"
                + " WHERE run_id=? AND source_checkpoint_id IS NULL",
            checkpointId.toString(),
            time,
            runId)
        != 1) throw new IllegalStateException("无法关联工作流的恢复检查点。");
    for (var entry : reused.entrySet()) {
      if (db.update(
              "UPDATE plan_nodes SET status='REUSED',source_agent_id=?,finished_at=?"
                  + " WHERE run_id=? AND node_key=? AND status='PENDING'",
              entry.getValue().toString(),
              time,
              runId,
              entry.getKey())
          != 1) throw new IllegalStateException("无法关联已核验的历史节点结果。");
    }
  }

  private static String graphFingerprint(
      List<PlanNode> nodes, WorkflowTemplate.Scope scope, String authorization, int maxAttempts) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      boolean workspaceGraph = scope.hasWorkspace();
      boolean hasBudgets = nodes.stream().anyMatch(node -> node.budget() != null);
      boolean hasChecks = nodes.stream().anyMatch(PlanNode::systemCheck);
      addFingerprintField(
          digest,
          hasChecks
              ? "workflow-graph-v5"
              : hasBudgets
                  ? "workflow-graph-v4"
                  : scope.allowsWrite()
                      ? "workflow-graph-v3"
                      : workspaceGraph ? "workflow-graph-v2" : "workflow-graph-v1");
      if (workspaceGraph) {
        addFingerprintField(digest, scope.name());
        addFingerprintField(digest, authorization);
      }
      for (var node : nodes) {
        if (hasChecks) {
          // v5 在保留原字段顺序的基础上加入节点类型和执行器引用，历史 Agent-only 摘要不变。
          for (String field :
              List.of(
                  node.id().toString(),
                  Integer.toString(node.sequence()),
                  node.key(),
                  node.label(),
                  node.phase(),
                  node.kind().name(),
                  node.role() == null ? "" : node.role().name(),
                  node.definitionRef() == null ? "" : node.definitionRef().id(),
                  node.definitionRef() == null
                      ? ""
                      : Integer.toString(node.definitionRef().version()),
                  node.definitionSha256() == null ? "" : node.definitionSha256(),
                  node.checkRef() == null ? "" : node.checkRef().id(),
                  node.checkRef() == null ? "" : Integer.toString(node.checkRef().version()),
                  Integer.toString(node.dependencyIds().size()))) {
            addFingerprintField(digest, field);
          }
        } else {
          for (String field :
              List.of(
                  node.id().toString(),
                  Integer.toString(node.sequence()),
                  node.key(),
                  node.label(),
                  node.phase(),
                  node.role().name(),
                  node.definitionRef().id(),
                  Integer.toString(node.definitionRef().version()),
                  node.definitionSha256(),
                  Integer.toString(node.dependencyIds().size()))) {
            addFingerprintField(digest, field);
          }
        }
        if (workspaceGraph) addFingerprintField(digest, Boolean.toString(node.workspaceRead()));
        if (scope.allowsWrite())
          addFingerprintField(digest, Boolean.toString(node.workspaceWrite()));
        if (hasBudgets) {
          var limit = node.budget();
          addFingerprintField(
              digest, limit == null ? "" : java.util.Objects.toString(limit.maxModelCalls(), ""));
          addFingerprintField(
              digest, limit == null ? "" : java.util.Objects.toString(limit.maxToolCalls(), ""));
        }
        for (UUID prerequisite : node.dependencyIds().stream().sorted().toList())
          addFingerprintField(digest, prerequisite.toString());
      }
      String graphSha256 = HexFormat.of().formatHex(digest.digest());
      return maxAttempts == 1
          ? graphSha256
          : AgentAssemblySnapshot.sha256(
              "workflow-attempt-policy-v1:" + maxAttempts + ":" + graphSha256);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("无法计算工作流图摘要。", e);
    }
  }

  private static void addFingerprintField(MessageDigest digest, String field) {
    byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
    digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
    digest.update(bytes);
  }

  /** 使用条件更新阻止重复启动或跳过尚未完成的前置步骤。 */
  public void start(String runId, String agentId, AgentRole role, String time) {
    var matching =
        find(runId).stream()
            .filter(node -> node.planVersionId() == null && node.role() == role)
            .toList();
    if (matching.size() != 1) throw new IllegalStateException("旧角色必须唯一对应一个计划节点。");
    startNode(runId, matching.getFirst().id(), agentId, time);
  }

  /**
   * 系统检查节点认领：条件更新为运行中，不写 agent_id。
   *
   * <p>与 Agent 路径共用依赖条件，避免检查节点在依赖未结算时被启动；调用方必须与检查记录写入处于同一事务。
   */
  public void startCheckNode(String runId, UUID nodeId, String time) {
    var saved = find(runId);
    var target =
        saved.stream()
            .filter(node -> node.id().equals(nodeId))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("计划中不存在该节点。"));
    if (!target.systemCheck() || target.agentId() != null)
      throw new IllegalStateException("只有未启动的系统检查节点可以认领。");
    if (!ready(saved).contains(nodeId.toString()))
      throw new IllegalStateException("检查节点不存在、已启动或前置步骤尚未完成。");
    int changed =
        db.update(
            """
            UPDATE plan_nodes SET status=?,started_at=?
            WHERE id=? AND run_id=? AND status=? AND agent_id IS NULL AND node_kind='SYSTEM_CHECK'
            AND NOT EXISTS (SELECT 1 FROM plan_node_dependencies d
              LEFT JOIN plan_nodes p ON p.id=d.prerequisite_id
              WHERE d.node_id=plan_nodes.id
                AND (d.run_id<>plan_nodes.run_id OR p.id IS NULL OR p.run_id<>plan_nodes.run_id
                  OR p.status NOT IN (?,?)))
            """,
            PlanNodeStatus.RUNNING.name(),
            time,
            nodeId.toString(),
            runId,
            PlanNodeStatus.PENDING.name(),
            PlanNodeStatus.COMPLETED.name(),
            PlanNodeStatus.REUSED.name());
    if (changed != 1) throw new IllegalStateException("检查节点不存在、已启动或前置步骤尚未完成。");
  }

  /** 结算已认领的系统检查节点；失败状态与阻塞原因由调用方在同一事务中保存。 */
  public void finishCheckNode(String runId, UUID nodeId, PlanNodeStatus status, String time) {
    if (status != PlanNodeStatus.COMPLETED && status != PlanNodeStatus.FAILED)
      throw new IllegalArgumentException("系统检查节点只能结算为完成或失败。");
    int changed =
        db.update(
            "UPDATE plan_nodes SET status=?,finished_at=? WHERE id=? AND run_id=? AND status=?"
                + " AND agent_id IS NULL AND node_kind='SYSTEM_CHECK'",
            status.name(),
            time,
            nodeId.toString(),
            runId,
            PlanNodeStatus.RUNNING.name());
    if (changed != 1) throw new IllegalStateException("没有可结算的系统检查节点。");
  }

  /** 旧角色用于定位接收节点；实际来源必须是图中已完成的直接前置节点。 */
  public void requireDirectDependencySender(String runId, AgentRole receiver, UUID senderId) {
    java.util.Objects.requireNonNull(receiver, "接收角色不能为空");
    java.util.Objects.requireNonNull(senderId, "发送实例标识不能为空");
    var nodes = find(runId);
    var receivers =
        nodes.stream()
            .filter(node -> node.planVersionId() == null && node.role() == receiver)
            .toList();
    if (receivers.size() != 1) throw new IllegalStateException("旧角色必须唯一对应一个接收节点。");
    var receiverNode = receivers.getFirst();
    boolean matches =
        nodes.stream()
            .filter(node -> receiverNode.dependencyIds().contains(node.id()))
            .anyMatch(
                node ->
                    (node.status() == PlanNodeStatus.COMPLETED && senderId.equals(node.agentId()))
                        || (node.status() == PlanNodeStatus.REUSED
                            && senderId.equals(node.sourceAgentId())));
    if (!matches) throw new IllegalStateException("交接发送实例不是已完成的直接前置节点。");
  }

  /** 按节点标识认领；调用方应将其与实例和交接写入放在同一事务中。 */
  public void startNode(String runId, UUID nodeId, String agentId, String time) {
    java.util.Objects.requireNonNull(nodeId, "节点标识不能为空");
    var saved = find(runId);
    if (saved.isEmpty()) throw new IllegalStateException("运行没有已保存的计划。");
    var target =
        saved.stream()
            .filter(node -> node.id().equals(nodeId))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("计划中不存在该节点。"));
    if (!ready(saved).contains(target.id().toString()))
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

  /** 从保存的图和节点状态取得第一个就绪节点；调用方仍需事务认领。 */
  public PlanNode nextReady(List<PlanNode> nodes) {
    var ready = ready(nodes);
    return nodes.stream()
        .filter(node -> ready.contains(node.id().toString()))
        .findFirst()
        .orElse(null);
  }

  private static List<String> ready(List<PlanNode> nodes) {
    var states = new java.util.LinkedHashMap<String, PlanNodeStatus>();
    for (var node : nodes) states.put(node.id().toString(), node.status());
    return graph(nodes).ready(states);
  }

  private static WorkflowGraph graph(List<PlanNode> nodes) {
    return new WorkflowGraph(
        nodes.stream()
            .map(
                node ->
                    new WorkflowGraph.Node(
                        node.id().toString(),
                        node.dependencyIds().stream().map(UUID::toString).toList()))
            .toList());
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

  /** 只重置当前节点指针；失败实例、尝试和交接仍可追溯。 */
  public void requeueFailedNode(String runId, UUID nodeId, String failedAgentId) {
    int changed =
        db.update(
            """
            UPDATE plan_nodes SET status='PENDING',agent_id=NULL,started_at=NULL,finished_at=NULL
            WHERE run_id=? AND id=? AND agent_id=? AND status='FAILED' AND plan_version_id IS NOT NULL
            """,
            runId,
            nodeId.toString(),
            failedAgentId);
    if (changed != 1) throw new IllegalStateException("无法重新排队已失败的节点。");
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
              String versionId = row.getString("plan_version_id");
              Integer nodeModels = (Integer) row.getObject("max_node_model_calls");
              Integer nodeTools = (Integer) row.getObject("max_node_tool_calls");
              PlanNodeKind kind = nodeKind(row.getString("node_kind"));
              String checkId = row.getString("check_id");
              Integer checkVersion = (Integer) row.getObject("check_version");
              if ((checkId == null) != (checkVersion == null))
                throw new IllegalStateException("计划节点的系统检查引用不完整。");
              if ((kind == PlanNodeKind.SYSTEM_CHECK) == (checkId == null))
                throw new IllegalStateException("计划节点的类型与执行器引用不一致。");
              SystemCheckRef checkRef =
                  checkId == null ? null : new SystemCheckRef(checkId, checkVersion);
              if ((definitionId == null) != (definitionVersion == null))
                throw new IllegalStateException("计划节点的 Agent 定义引用不完整。");
              // 历史角色计划可能没有保存定义引用；版本化 Agent 节点必须完整。
              if (versionId != null && (kind == PlanNodeKind.AGENT) == (definitionId == null))
                throw new IllegalStateException("计划节点的类型与 Agent 定义不一致。");
              if ((fingerprint != null && !fingerprint.matches("[0-9a-f]{64}"))
                  || (definitionId == null && fingerprint != null))
                throw new IllegalStateException("计划节点的 Agent 定义摘要无效。");
              AgentDefinitionRef definition =
                  definitionId == null
                      ? null
                      : new AgentDefinitionRef(definitionId, definitionVersion);
              String role = row.getString(versionId == null ? "role_code" : "permission_role");
              return new PlanNode(
                  UUID.fromString(row.getString("id")),
                  UUID.fromString(row.getString("run_id")),
                  row.getInt("sequence_no"),
                  role == null ? null : AgentRole.valueOf(role),
                  prerequisite == null ? null : AgentRole.valueOf(prerequisite),
                  PlanNodeStatus.valueOf(row.getString("status")),
                  agent == null ? null : UUID.fromString(agent),
                  sourceAgent == null ? null : UUID.fromString(sourceAgent),
                  started == null ? null : Instant.parse(started),
                  finished == null ? null : Instant.parse(finished),
                  List.of(),
                  definition,
                  fingerprint,
                  versionId == null ? null : UUID.fromString(versionId),
                  row.getString("node_key"),
                  row.getString("node_label"),
                  row.getString("phase_key"),
                  row.getBoolean("workspace_read"),
                  row.getBoolean("workspace_write"),
                  nodeModels == null && nodeTools == null
                      ? null
                      : new WorkflowNodeBudget(nodeModels, nodeTools),
                  kind,
                  checkRef);
            },
            runId);
    if (rows.isEmpty()) return List.of();
    var byId = new java.util.LinkedHashMap<UUID, PlanNode>();
    var byRole = new java.util.EnumMap<AgentRole, UUID>(AgentRole.class);
    var dependencies = new java.util.LinkedHashMap<UUID, java.util.List<UUID>>();
    for (var row : rows) {
      byId.put(row.id(), row);
      if (row.planVersionId() == null) byRole.put(row.role(), row.id());
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
          || !byId.containsKey(edge.prerequisiteId())
          || !java.util.Objects.equals(
              byId.get(edge.nodeId()).planVersionId(),
              byId.get(edge.prerequisiteId()).planVersionId()))
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
              row.definitionSha256(),
              row.planVersionId(),
              row.key(),
              row.label(),
              row.phase(),
              row.workspaceRead(),
              row.workspaceWrite(),
              row.budget(),
              row.kind(),
              row.checkRef()));
    }
    graph(result);
    return List.copyOf(result);
  }

  private static PlanNodeKind nodeKind(String value) {
    if (value == null) return PlanNodeKind.AGENT;
    try {
      return PlanNodeKind.valueOf(value);
    } catch (IllegalArgumentException e) {
      throw new IllegalStateException("计划节点的执行器类型不受支持：" + value + "。", e);
    }
  }

  private record Dependency(String runId, UUID nodeId, UUID prerequisiteId) {}
}
