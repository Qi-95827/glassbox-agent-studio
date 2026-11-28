package dev.workbench.planning;

import dev.workbench.domain.AgentRole;
import dev.workbench.domain.ToolKind;
import dev.workbench.framework.agent.definition.AgentDefinition;
import dev.workbench.framework.agent.definition.AgentDefinitionCatalog;
import dev.workbench.framework.workflow.check.SystemCheckRegistry;
import dev.workbench.framework.workflow.plan.PlanNode;
import dev.workbench.framework.workflow.plan.PlanNodeSpec;
import dev.workbench.framework.workflow.plan.RevisionEdge;
import dev.workbench.framework.workflow.plan.WorkflowPlan;
import dev.workbench.framework.workflow.plan.WorkflowTemplate;
import java.util.List;
import java.util.UUID;

/**
 * 计划执行前的图与权限校验：节点规格、装配绑定、反思回边必须与保存的图摘要逐项一致。
 *
 * <p>校验只读取已持久化内容并抛出领域异常，不修改任何记录；通过后调用方才可以认领节点。
 */
final class PlanExecutionGuard {
  private final PlanNodeViewMapper views;
  private final AgentDefinitionCatalog definitions;
  private final SystemCheckRegistry checks;

  PlanExecutionGuard(
      PlanNodeViewMapper views, AgentDefinitionCatalog definitions, SystemCheckRegistry checks) {
    this.views = views;
    this.definitions = definitions;
    this.checks = checks;
  }

  /** 复核指定计划版本的节点、规格与绑定；任何不一致都阻止执行。 */
  List<PlanNode> checkedWorkflowNodes(
      String runId, WorkflowPlan plan, List<PlanNode> nodes, boolean bindingStoreConfigured) {
    var specs = views.specsByNode(runId);
    var nodeBindings = views.bindingsByNode(runId);
    var edges = views.revisionEdges(runId);
    long agentNodes = nodes.stream().filter(node -> !node.systemCheck()).count();
    if (bindingStoreConfigured && !nodeBindings.isEmpty() && nodeBindings.size() != agentNodes)
      throw new IllegalStateException("计划节点的 Agent 选择记录不完整，不能执行。");
    if (!specs.isEmpty() && specs.size() != agentNodes)
      throw new IllegalStateException("计划节点规格不完整，不能执行。");
    validateRevisionEdges(nodes, edges);
    if (nodes.isEmpty()
        || nodes.stream().anyMatch(node -> !plan.id().equals(node.planVersionId()))
        || !PlanGraphFingerprint.fingerprint(
                nodes,
                plan.scope(),
                plan.authorizationSha256(),
                plan.maxAttempts(),
                specs,
                nodeBindings,
                edges)
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
          || !definitions.fingerprint(node.definitionRef()).equals(node.definitionSha256()))
        throw new IllegalStateException("工作流引用的定义版本或只读权限与保存的计划不一致。");
      if (specs.isEmpty()) {
        if (node.workspaceRead() != !definition.tools().isEmpty()
            || node.workspaceWrite() != definition.tools().contains(ToolKind.WORKSPACE_WRITE))
          throw new IllegalStateException("工作流引用的定义版本或只读权限与保存的计划不一致。");
        continue;
      }
      var spec = specs.get(node.id());
      if (spec == null) throw new IllegalStateException("计划节点缺少文件范围规格。");
      String recomputed =
          PlanNodeSpec.digest(
              spec.candidateSha256(),
              spec.key(),
              spec.readFiles(),
              spec.writeFiles(),
              spec.outputs(),
              spec.acceptanceCriteria());
      if (!recomputed.equals(spec.sha256())
          || node.workspaceRead() != !spec.readFiles().isEmpty()
          || node.workspaceWrite() != !spec.writeFiles().isEmpty())
        throw new IllegalStateException("计划节点的文件范围规格与保存摘要不一致。");
    }
    return nodes;
  }

  /** 定义的角色与工具必须落在模板授权范围内。 */
  static void validateDefinition(WorkflowTemplate.Scope scope, AgentDefinition definition) {
    boolean profileAllowed =
        definition.permissionProfile() == AgentRole.ANALYST
            || definition.permissionProfile() == AgentRole.REVIEWER
            || (scope.allowsWrite() && definition.permissionProfile() == AgentRole.CODER);
    if (!profileAllowed || definition.tools().stream().anyMatch(tool -> !scope.permits(tool)))
      throw new IllegalArgumentException("工作流的 Agent 定义超出模板的权限上限。");
  }

  /** 每个写入节点都必须有一个只读、直接依赖它的审查节点。 */
  static void requireIndependentReview(List<PlanNode> nodes) {
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

  /** 反思回边必须指向当前计划内的节点，且审查节点唯一、目标类型与计数范围合法。 */
  private static void validateRevisionEdges(
      List<PlanNode> nodes, List<PlanService.RevisionEdgeState> edges) {
    var byId = new java.util.LinkedHashMap<UUID, PlanNode>();
    for (var node : nodes) byId.put(node.id(), node);
    var reviewers = new java.util.HashSet<UUID>();
    for (var edge : edges) {
      var reviewer = byId.get(edge.reviewerNodeId());
      var target = byId.get(edge.targetNodeId());
      if (reviewer == null || target == null)
        throw new IllegalStateException("反思回边引用了不属于当前计划的节点。");
      if (!reviewers.add(edge.reviewerNodeId()))
        throw new IllegalStateException("同一审查节点存在多条反思回边。");
      if (reviewer.role() != AgentRole.REVIEWER)
        throw new IllegalStateException("只有审查节点可以声明反思回边。");
      if (target.role() != AgentRole.CODER && target.role() != AgentRole.ANALYST)
        throw new IllegalStateException("反思回边只能指向开发或分析节点。");
      if (!reviewer.dependencyIds().contains(target.id()))
        throw new IllegalStateException("反思回边目标不是审查节点的直接上游。");
      if (edge.maxRevisions() < 1 || edge.maxRevisions() > RevisionEdge.HARD_MAX_REVISIONS)
        throw new IllegalStateException("反思次数超出允许范围。");
      if (edge.revisionCount() < 0 || edge.revisionCount() > edge.maxRevisions())
        throw new IllegalStateException("反思回边计数无效。");
    }
  }
}
