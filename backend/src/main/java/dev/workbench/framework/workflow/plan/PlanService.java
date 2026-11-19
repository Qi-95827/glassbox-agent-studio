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
import dev.workbench.persistence.repository.PlanGraphRepository;
import dev.workbench.persistence.repository.PlanStateRepository;
import dev.workbench.persistence.entity.PlanNodeEntity;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** 保存兼容计划与版本化工作流，节点状态使用同一套条件更新。*/
@Service
public class PlanService {
  private final PlanStateRepository states;
  private final PlanFactory factory;
  private final AgentDefinitionRegistry definitions;
  private final SystemCheckRegistry checks;
  private final PlanGraphRepository graphStore;

  public PlanService(
      PlanStateRepository states,
      PlanFactory factory,
      AgentDefinitionRegistry definitions,
      SystemCheckRegistry checks,
      PlanGraphRepository graphStore) {
    this.states = states;
    this.factory = factory;
    this.definitions = java.util.Objects.requireNonNull(definitions, "Agent 定义目录不能为空");
    this.checks = java.util.Objects.requireNonNull(checks, "系统检查目录不能为。");
    this.graphStore = java.util.Objects.requireNonNull(graphStore, "计划存储不能为空");
  }

  /** 节点与依赖必须在同一事务写入；当前只创建服务端支持的固定计划。*/
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
      var saved =
          new PlanNodeEntity(ids.get(workflowNode.id()), runId, ++sequence, PlanNodeStatus.PENDING);
      saved.setRoleCode(node.role().name());
      saved.setPrerequisiteRole(prerequisite == null ? null : prerequisite.name());
      saved.setDefinition(definition.id(), definition.version(), fingerprint);
      graphStore.saveNode(saved);
    }
    saveEdges(runId, workflow, ids);
  }

  /** 由创建运行事务调用，先保存计划和节点，再保存引用这些节点的边。*/
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
    graphStore.savePlan(
        versionId,
        runId,
        template.id(),
        template.version(),
        reviewRequired,
        reviewRequired ? null : time,
        scope.name(),
        authorization,
        template.maxAttempts());
    // 节点外键引用计划头，先让同一事务中的后续读取看到计划行。
graphStore.flush();
    int sequence = 0;
    for (var step : template.steps()) {
      String nodeId = UUID.randomUUID().toString();
      ids.put(step.key(), nodeId);
      if (step.kind() == PlanNodeKind.SYSTEM_CHECK) {
        // 检查只读取已结算的前置结果，不能使用 Agent 定义、角色或工具权限。
        int nodeSequence = ++sequence;
        var saved = new PlanNodeEntity(nodeId, runId, nodeSequence, PlanNodeStatus.PENDING);
        saved.setPlanVersionId(versionId);
        saved.setNodeKey(step.key());
        saved.setNodeLabel(step.label());
        saved.setPhaseKey(step.phase());
        saved.setWorkspaceRead(false);
        saved.setWorkspaceWrite(false);
        saved.setNodeKind(PlanNodeKind.SYSTEM_CHECK);
        saved.setCheck(step.check().id(), step.check().version());
        graphStore.saveNode(saved);
        continue;
      }
      var definition = definitions.resolve(step.agent());
      boolean workspaceRead = !definition.tools().isEmpty();
      boolean workspaceWrite = definition.tools().contains(ToolKind.WORKSPACE_WRITE);
      int nodeSequence = ++sequence;
      var saved = new PlanNodeEntity(nodeId, runId, nodeSequence, PlanNodeStatus.PENDING);
      saved.setDefinition(
          step.agent().id(), step.agent().version(), definitions.fingerprint(step.agent()));
      saved.setPlanVersionId(versionId);
      saved.setNodeKey(step.key());
      saved.setNodeLabel(step.label());
      saved.setPhaseKey(step.phase());
      saved.setPermissionRole(definition.permissionProfile().name());
      saved.setWorkspaceRead(workspaceRead);
      saved.setWorkspaceWrite(workspaceWrite);
      saved.setMaxNodeModelCalls(step.budget() == null ? null : step.budget().maxModelCalls());
      saved.setMaxNodeToolCalls(step.budget() == null ? null : step.budget().maxToolCalls());
      saved.setNodeKind(PlanNodeKind.AGENT);
      graphStore.saveNode(saved);
    }
    if (scope.hasWorkspace() && !hasWorkspaceReader)
      throw new IllegalArgumentException("工作区模板缺少读取节点。");
    saveEdges(runId, workflow, ids);
    graphStore.flush();
    var nodes = find(runId);
    if (scope.allowsWrite()) requireIndependentReview(nodes);
    String graphFingerprint = graphFingerprint(nodes, scope, authorization, template.maxAttempts());
    graphStore.updateGraphSha256(versionId, graphFingerprint);
    graphStore.flush();
  }

  private void saveEdges(
      String runId, WorkflowDefinition workflow, java.util.Map<String, String> ids) {
    for (var node : workflow.nodes()) {
      for (var prerequisite : node.dependencies()) {
        graphStore.saveEdge(runId, ids.get(node.id()), ids.get(prerequisite));
      }
    }
  }

  public WorkflowPlan workflowPlan(String runId) {
    return graphStore.findPlan(runId);
  }

  /** 固定计划的摘要不含状态与时间，用于发现图或装配引用发生意外变化。*/
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

  /** 恢复副本留存原计划来源；已核验结果只关联历史实例。*/
  public void attachRecovery(
      String runId, UUID checkpointId, java.util.Map<String, UUID> reused, String time) {
    if (graphStore.attachRecovery(runId, checkpointId, time) != 1)
      throw new IllegalStateException("无法关联工作流的恢复检查点。");
    for (var entry : reused.entrySet()) {
      if (!states.reuseNode(runId, entry.getKey(), entry.getValue().toString(), time))
        throw new IllegalStateException("无法关联已核验的历史节点结果。");
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

  /** 使用条件更新阻止重复启动或跳过尚未完成的前置步骤。*/
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
    if (!states.claimCheck(runId, nodeId.toString(), time))
      throw new IllegalStateException("检查节点不存在、已启动或前置步骤尚未完成。");
  }

  /** 结算已认领的系统检查节点；失败状态与阻塞原因由调用方在同一事务中保存。*/
  public void finishCheckNode(String runId, UUID nodeId, PlanNodeStatus status, String time) {
    if (status != PlanNodeStatus.COMPLETED && status != PlanNodeStatus.FAILED)
      throw new IllegalArgumentException("系统检查节点只能结算为完成或失败。");
    if (!states.finishCheck(runId, nodeId.toString(), status, time))
      throw new IllegalStateException("没有可结算的系统检查节点。");
  }

  /** 旧角色用于定位接收节点；实际来源必须是图中已完成的直接前置节点。*/
  public void requireDirectDependencySender(String runId, AgentRole receiver, UUID senderId) {
    java.util.Objects.requireNonNull(receiver, "接收角色不能为空");
    java.util.Objects.requireNonNull(senderId, "发送实例标识不能为。");
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

  /** 按节点标识认领；调用方应将其与实例和交接写入放在同一事务中。*/
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
    // 持久化层在认领时重验目标状态和依赖。
if (!states.claimAgent(runId, target.id().toString(), agentId, time))
      throw new IllegalStateException("步骤不存在、已启动或前置步骤尚未完成。");
  }

  /** 从保存的图和节点状态取得第一个就绪节点；调用方仍需事务认领。*/
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

  /** 复用已校验的历史分析，不伪造本运行中的 Agent 调用。*/
  public void reuseAnalysis(String runId, String sourceAgentId, String time) {
    if (!states.reuseAnalysis(runId, sourceAgentId, time))
      throw new IllegalStateException("分析步骤不能复用。");
  }

  /** 调用与步骤共享终态；调用方应确保与 Agent 调用更新处于同一事务。 */
  public void finish(String agentId, InvocationStatus status, String time) {
    if (status == InvocationStatus.RUNNING) throw new IllegalArgumentException("调用尚未结束。");
    if (!states.finishAgent(agentId, PlanNodeStatus.valueOf(status.name()), time))
      throw new IllegalStateException("步骤不存在或已经结束。");
  }

  /** 只重置当前节点指针；失败实例、尝试和交接仍可追溯。*/
  public void requeueFailedNode(String runId, UUID nodeId, String failedAgentId) {
    if (!states.requeueFailed(runId, nodeId.toString(), failedAgentId))
      throw new IllegalStateException("无法重新排队已失败的节点。");
  }

  /** 终止运行时保留已结束步骤；在途步骤结果未知，未启动的步骤标记跳过。*/
  public void settle(String runId, String time) {
    states.settle(runId, time);
  }

  /** 返回计划与真实保存的所有前置节点；异常关系不能被当作无依赖。*/
  public List<PlanNode> find(String runId) {
    List<PlanNode> rows = states.findNodes(runId).stream().map(PlanService::readNode).toList();

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
        states.findEdges(runId).stream()
            .map(
                row ->
                    new Dependency(
                        row.runId(),
                        UUID.fromString(row.nodeId()),
                        UUID.fromString(row.prerequisiteId())))
            .toList();

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

  private static PlanNode readNode(PlanStateRepository.NodeRow row) {
    PlanNodeKind kind = row.nodeKind() == null ? PlanNodeKind.AGENT : row.nodeKind();
    String checkId = row.checkId();
    if ((checkId == null) != (row.checkVersion() == null))
      throw new IllegalStateException("计划节点的系统检查引用不完整。");
    if ((kind == PlanNodeKind.SYSTEM_CHECK) == (checkId == null))
      throw new IllegalStateException("计划节点的类型与执行器引用不一致。");
    String definitionId = row.definitionId();
    if ((definitionId == null) != (row.definitionVersion() == null))
      throw new IllegalStateException("计划节点与 Agent 定义引用不完整。");
    if (row.planVersionId() != null && (kind == PlanNodeKind.AGENT) == (definitionId == null))
      throw new IllegalStateException("计划节点的类型与 Agent 定义不一致。");
    String fingerprint = row.definitionSha256();
    if ((fingerprint != null && !fingerprint.matches("[0-9a-f]{64}"))
        || (definitionId == null && fingerprint != null))
      throw new IllegalStateException("计划节点与 Agent 定义摘要无效。");
    String role = row.planVersionId() == null ? row.roleCode() : row.permissionRole();
    return new PlanNode(
        UUID.fromString(row.id()),
        UUID.fromString(row.runId()),
        row.sequenceNo(),
        role == null ? null : AgentRole.valueOf(role),
        row.prerequisiteRole() == null ? null : AgentRole.valueOf(row.prerequisiteRole()),
        row.status(),
        parseUuid(row.agentId()),
        parseUuid(row.sourceAgentId()),
        row.startedAt() == null ? null : Instant.parse(row.startedAt()),
        row.finishedAt() == null ? null : Instant.parse(row.finishedAt()),
        List.of(),
        definitionId == null ? null : new AgentDefinitionRef(definitionId, row.definitionVersion()),
        fingerprint,
        parseUuid(row.planVersionId()),
        row.nodeKey(),
        row.nodeLabel(),
        row.phaseKey(),
        row.workspaceRead(),
        row.workspaceWrite(),
        row.maxNodeModelCalls() == null && row.maxNodeToolCalls() == null
            ? null
            : new WorkflowNodeBudget(row.maxNodeModelCalls(), row.maxNodeToolCalls()),
        kind,
        checkId == null ? null : new SystemCheckRef(checkId, row.checkVersion()));
  }

  private static UUID parseUuid(String value) {
    return value == null ? null : UUID.fromString(value);
  }

  private record Dependency(String runId, UUID nodeId, UUID prerequisiteId) {}
}