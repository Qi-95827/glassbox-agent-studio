package dev.workbench.planning;

import dev.workbench.domain.AgentRole;
import dev.workbench.domain.TaskDefinition;
import dev.workbench.domain.ToolKind;
import dev.workbench.framework.agent.definition.AgentDefinition;
import dev.workbench.framework.agent.definition.AgentDefinitionCatalog;
import dev.workbench.framework.workflow.check.SystemCheckRegistry;
import dev.workbench.framework.workflow.plan.CandidateRunPlan;
import dev.workbench.framework.workflow.plan.PlanDefinition;
import dev.workbench.framework.workflow.plan.PlanFactory;
import dev.workbench.framework.workflow.plan.PlanNodeKind;
import dev.workbench.framework.workflow.plan.PlanNodeSpec;
import dev.workbench.framework.workflow.plan.PlanNodeStatus;
import dev.workbench.framework.workflow.plan.RevisionEdge;
import dev.workbench.framework.workflow.plan.WorkflowDefinition;
import dev.workbench.framework.workflow.plan.WorkflowTemplate;
import dev.workbench.persistence.entity.PlanNodeAgentBindingEntity;
import dev.workbench.persistence.entity.PlanNodeEntity;
import dev.workbench.persistence.entity.PlanNodeSpecEntity;
import dev.workbench.persistence.entity.PlanRevisionEdgeEntity;
import dev.workbench.persistence.repository.PlanGraphRepository;
import dev.workbench.persistence.repository.PlanNodeAgentBindingRepository;
import dev.workbench.persistence.repository.PlanNodeSpecRepository;
import dev.workbench.persistence.repository.PlanRevisionEdgeRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 计划物化：把固定计划、受控模板与已确认候选写成计划头、节点、依赖、节点规格、Agent 绑定与反思回边。
 *
 * <p>调用方必须已经开启事务：这里只做写入与受信引用核对，不自行提交，也不会在失败时留下半成品计划。
 */
final class PlanInitializationWriter {
  private final PlanFactory factory;
  private final AgentDefinitionCatalog definitions;
  private final SystemCheckRegistry checks;
  private final PlanGraphRepository graphStore;
  private final PlanNodeSpecRepository specStore;
  private final PlanNodeAgentBindingRepository bindings;
  private final PlanRevisionEdgeRepository revisionEdgeStore;
  private final PlanNodeViewMapper views;

  PlanInitializationWriter(
      PlanFactory factory,
      AgentDefinitionCatalog definitions,
      SystemCheckRegistry checks,
      PlanGraphRepository graphStore,
      PlanNodeSpecRepository specStore,
      PlanNodeAgentBindingRepository bindings,
      PlanRevisionEdgeRepository revisionEdgeStore,
      PlanNodeViewMapper views) {
    this.factory = factory;
    this.definitions = definitions;
    this.checks = checks;
    this.graphStore = graphStore;
    this.specStore = specStore;
    this.bindings = bindings;
    this.revisionEdgeStore = revisionEdgeStore;
    this.views = views;
  }

  /** 节点与依赖必须在同一事务写入；当前只创建服务端支持的固定计划。 */
  void initialize(String runId, boolean includeCoder) {
    PlanDefinition plan = factory.create(includeCoder);
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

  /** 由创建运行事务调用，先保存计划和节点，再保存引用这些节点的边。 */
  void initializeWorkflow(
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
      PlanExecutionGuard.validateDefinition(scope, definition);
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
    var nodes = views.find(runId);
    if (scope.allowsWrite()) PlanExecutionGuard.requireIndependentReview(nodes);
    String graphFingerprint =
        PlanGraphFingerprint.fingerprint(nodes, scope, authorization, template.maxAttempts());
    graphStore.updateGraphSha256(versionId, graphFingerprint);
    graphStore.flush();
  }

  /**
   * 把已确认的候选计划物化为运行计划、节点规格和依赖。
   *
   * <p>节点文件范围、产出契约和候选摘要一起保存；确认事务提交后运行才能派发。图摘要使用 v6 协议，额外绑定每个节点的规格摘要，防止持久化规格被静默替换。
   */
  void initializeFromCandidate(
      String runId, CandidateRunPlan candidatePlan, TaskDefinition task, String time) {
    var scope =
        task.writeEnabled()
            ? WorkflowTemplate.Scope.WORKSPACE_WRITE_AUTHORIZED
            : WorkflowTemplate.Scope.WORKSPACE_READ_ONLY;
    String authorization = scope.authorizationDigest(task);
    String versionId = UUID.randomUUID().toString();
    var definitionsByKey = new java.util.LinkedHashMap<String, AgentDefinition>();
    for (var node : candidatePlan.nodes()) {
      var definition = definitions.resolve(node.definition());
      PlanExecutionGuard.validateDefinition(scope, definition);
      if (!node.readFiles().isEmpty() && definition.tools().isEmpty())
        throw new IllegalArgumentException("读取工作区的节点缺少受信工具定义。");
      if (!node.writeFiles().isEmpty() && !definition.tools().contains(ToolKind.WORKSPACE_WRITE))
        throw new IllegalArgumentException("写入节点缺少受信写入工具。");
      definitionsByKey.put(node.key(), definition);
    }
    graphStore.savePlan(
        versionId, runId, "plan-candidate", 1, false, time, scope.name(), authorization, 2);
    graphStore.flush();
    var ids = new java.util.LinkedHashMap<String, String>();
    int sequence = 0;
    for (var node : candidatePlan.nodes()) {
      var definition = definitionsByKey.get(node.key());
      String nodeId = UUID.randomUUID().toString();
      ids.put(node.key(), nodeId);
      var saved = new PlanNodeEntity(nodeId, runId, ++sequence, PlanNodeStatus.PENDING);
      saved.setPlanVersionId(versionId);
      saved.setNodeKey(node.key());
      saved.setNodeLabel(node.title());
      saved.setPhaseKey(node.phase());
      saved.setDefinition(
          node.definition().id(),
          node.definition().version(),
          definitions.fingerprint(node.definition()));
      saved.setPermissionRole(definition.permissionProfile().name());
      saved.setWorkspaceRead(!node.readFiles().isEmpty());
      saved.setWorkspaceWrite(!node.writeFiles().isEmpty());
      saved.setNodeKind(PlanNodeKind.AGENT);
      graphStore.saveNode(saved);
      saveBinding(saved, runId, node, definition, time);
      String specSha =
          PlanNodeSpec.digest(
              candidatePlan.candidateSha256(),
              node.key(),
              node.readFiles(),
              node.writeFiles(),
              node.outputs(),
              node.acceptanceCriteria());
      specStore.save(
          new PlanNodeSpecEntity(
              nodeId,
              runId,
              node.key(),
              candidatePlan.candidateSha256(),
              views.writeJson(node.readFiles()),
              views.writeJson(node.writeFiles()),
              views.writeJson(node.outputs()),
              views.writeJson(node.acceptanceCriteria()),
              specSha));
    }
    for (var node : candidatePlan.nodes())
      for (String dependency : node.dependsOn())
        graphStore.saveEdge(runId, ids.get(node.key()), ids.get(dependency));
    persistedRevisionEdges(runId, ids, candidatePlan.revisionEdges(), time);
    graphStore.flush();
    var nodes = views.find(runId);
    if (scope.allowsWrite()) PlanExecutionGuard.requireIndependentReview(nodes);
    graphStore.updateGraphSha256(
        versionId,
        PlanGraphFingerprint.fingerprint(
            nodes,
            scope,
            authorization,
            2,
            views.specsByNode(runId),
            views.bindingsByNode(runId),
            views.revisionEdges(runId)));
    graphStore.flush();
  }

  /** 持久化节点选择的内置/私有定义版本、理由和组件快照；内置也记录来源以便页面解释。 */
  private void saveBinding(
      PlanNodeEntity node,
      String runId,
      CandidateRunPlan.Node planned,
      AgentDefinition definition,
      String time) {
    if (bindings == null) return;
    var binding = planned.binding();
    String components =
        binding == null
            ? null
            : views.writeJson(
                Map.of(
                    "skills", binding.skills(),
                    "tools", binding.tools()));
    bindings.save(
        new PlanNodeAgentBindingEntity(
            node.getId(),
            runId,
            definitions.isPrivate(planned.definition()) ? "PRIVATE" : "BUILTIN",
            planned.definition().id(),
            planned.definition().version(),
            binding == null ? definitions.fingerprint(planned.definition()) : binding.specSha256(),
            binding == null ? "BUILTIN" : binding.source(),
            binding == null ? "未指定私有 Agent，沿用角色对应的内置定义。" : binding.reason(),
            binding == null ? definition.name() : binding.name(),
            components,
            time));
  }

  /** 把候选声明的反思回边落库；节点键通过同一物化映射转成持久化节点标识。 */
  private void persistedRevisionEdges(
      String runId, Map<String, String> ids, List<RevisionEdge> edges, String time) {
    if (revisionEdgeStore == null || edges.isEmpty()) return;
    for (var edge : edges) {
      String reviewerId = ids.get(edge.fromReviewerNodeKey());
      String targetId = ids.get(edge.toTargetNodeKey());
      if (reviewerId == null || targetId == null)
        throw new IllegalArgumentException("反思回边引用了未物化的计划节点。");
      revisionEdgeStore.save(
          new PlanRevisionEdgeEntity(
              UUID.randomUUID().toString(),
              runId,
              reviewerId,
              targetId,
              edge.maxRevisions(),
              0,
              null,
              time,
              time));
    }
  }

  /** 按模板依赖保存节点边；节点键与持久化标识由调用方在同一事务内提供。 */
  private void saveEdges(
      String runId, WorkflowDefinition workflow, Map<String, String> ids) {
    for (var node : workflow.nodes()) {
      for (var prerequisite : node.dependencies()) {
        graphStore.saveEdge(runId, ids.get(node.id()), ids.get(prerequisite));
      }
    }
  }
}
