package dev.workbench.planning;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.workbench.domain.AgentRole;
import dev.workbench.domain.InvocationStatus;
import dev.workbench.domain.TaskDefinition;
import dev.workbench.framework.agent.definition.AgentDefinitionCatalog;
import dev.workbench.framework.agent.definition.AgentDefinitionRegistry;
import dev.workbench.framework.workflow.check.SystemCheckRegistry;
import dev.workbench.framework.workflow.plan.*;
import dev.workbench.persistence.repository.PlanGraphRepository;
import dev.workbench.persistence.repository.PlanNodeAgentBindingRepository;
import dev.workbench.persistence.repository.PlanNodeSpecRepository;
import dev.workbench.persistence.repository.PlanRevisionEdgeRepository;
import dev.workbench.persistence.repository.PlanStateRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 保存兼容计划与版本化工作流，节点状态使用同一套条件更新。
 *
 * <p>物化、行到视图的映射、图摘要与执行前校验分别由同包协作类承担；本类保留事务入口与条件更新调用，
 * 全部既有公开方法签名保持不变，因此调用方与测试无需感知拆分。
 */
@Service
public class PlanService implements dev.workbench.framework.workflow.plan.PlanOperations {
  private final PlanStateRepository states;
  private final PlanGraphRepository graphStore;
  private final PlanNodeAgentBindingRepository bindings;
  private final PlanRevisionEdgeRepository revisionEdgeStore;
  private final PlanNodeViewMapper views;
  private final PlanExecutionGuard executionGuard;
  private final PlanInitializationWriter initializer;

  @Autowired
  public PlanService(
      PlanStateRepository states,
      PlanFactory factory,
      AgentDefinitionCatalog definitions,
      SystemCheckRegistry checks,
      PlanGraphRepository graphStore,
      PlanNodeSpecRepository specStore,
      PlanNodeAgentBindingRepository bindings,
      PlanRevisionEdgeRepository revisionEdgeStore,
      ObjectMapper json) {
    this.states = states;
    var checkedDefinitions = java.util.Objects.requireNonNull(definitions, "Agent 定义目录不能为空");
    var checkedChecks = java.util.Objects.requireNonNull(checks, "系统检查目录不能为。");
    this.graphStore = java.util.Objects.requireNonNull(graphStore, "计划存储不能为空");
    var checkedSpecStore = java.util.Objects.requireNonNull(specStore, "节点规格存储不能为空");
    this.bindings = bindings;
    this.revisionEdgeStore = revisionEdgeStore;
    var checkedJson = java.util.Objects.requireNonNull(json, "计划 JSON 处理器不能为空");
    this.views =
        new PlanNodeViewMapper(states, checkedSpecStore, bindings, revisionEdgeStore, checkedJson);
    this.executionGuard = new PlanExecutionGuard(this.views, checkedDefinitions, checkedChecks);
    this.initializer =
        new PlanInitializationWriter(
            factory,
            checkedDefinitions,
            checkedChecks,
            this.graphStore,
            checkedSpecStore,
            bindings,
            revisionEdgeStore,
            this.views);
  }

  /** 兼容旧单元验证：只有内置定义，没有节点 Agent 绑定与反思回边存储。 */
  public PlanService(
      PlanStateRepository states,
      PlanFactory factory,
      AgentDefinitionRegistry definitions,
      SystemCheckRegistry checks,
      PlanGraphRepository graphStore,
      PlanNodeSpecRepository specStore,
      ObjectMapper json) {
    this(
        states,
        factory,
        new AgentDefinitionCatalog(definitions),
        checks,
        graphStore,
        specStore,
        null,
        null,
        json);
  }

  /** 节点与依赖必须在同一事务写入；当前只创建服务端支持的固定计划。 */
  @org.springframework.transaction.annotation.Transactional
  public void initialize(String runId, boolean includeCoder) {
    initializer.initialize(runId, includeCoder);
  }

  /** 由创建运行事务调用，先保存计划和节点，再保存引用这些节点的边。 */
  @org.springframework.transaction.annotation.Transactional
  public void initializeWorkflow(
      String runId,
      WorkflowTemplate template,
      TaskDefinition task,
      boolean reviewRequired,
      String time) {
    initializer.initializeWorkflow(runId, template, task, reviewRequired, time);
  }

  /**
   * 把已确认的候选计划物化为运行计划、节点规格和依赖。
   *
   * <p>节点文件范围、产出契约和候选摘要一起保存；确认事务提交后运行才能派发。图摘要使用 v6 协议，额外绑定每个节点的规格摘要，防止持久化规格被静默替换。
   */
  @org.springframework.transaction.annotation.Transactional
  public void initializeFromCandidate(
      String runId, CandidateRunPlan candidatePlan, TaskDefinition task, String time) {
    initializer.initializeFromCandidate(runId, candidatePlan, task, time);
  }

  /** 运行中持久化的反思回边；历史计划没有记录时返回空。 */
  public List<RevisionEdgeState> revisionEdges(String runId) {
    return views.revisionEdges(runId);
  }

  public RevisionEdgeState revisionEdgeForReviewer(String runId, UUID reviewerNodeId) {
    return revisionEdges(runId).stream()
        .filter(edge -> edge.reviewerNodeId().equals(reviewerNodeId))
        .findFirst()
        .orElse(null);
  }

  /** 反思重试不扩大常规尝试上限，但为目标与审查节点各自额外开放 maxRevisions 次。 */
  public boolean allowsReflectionAttempt(String runId, UUID nodeId, int attemptNumber) {
    var plan = workflowPlan(runId);
    if (plan == null || attemptNumber <= plan.maxAttempts()) return true;
    int extra =
        revisionEdges(runId).stream()
            .filter(edge -> edge.reviewerNodeId().equals(nodeId) || edge.targetNodeId().equals(nodeId))
            .mapToInt(RevisionEdgeState::maxRevisions)
            .sum();
    return attemptNumber <= plan.maxAttempts() + extra;
  }

  /** 节点当前反思轮次：目标与审查节点都取关联回边的已触发次数。 */
  public int revisionIndexFor(String runId, UUID nodeId) {
    return revisionEdges(runId).stream()
        .filter(edge -> edge.reviewerNodeId().equals(nodeId) || edge.targetNodeId().equals(nodeId))
        .mapToInt(RevisionEdgeState::revisionCount)
        .max()
        .orElse(0);
  }

  /**
   * 触发一次反思：目标与审查节点回到待执行，回边计数加一并保存本轮评审结论。
   *
   * <p>调用方必须与审查节点结算处于同一事务；下游节点永远不会在反思完成前看到审查完成状态。
   */
  public void triggerRevision(
      String runId, UUID reviewerNodeId, UUID targetNodeId, String verdictJson, String time) {
    if (revisionEdgeStore == null) throw new IllegalStateException("反思回边存储不可用。");
    var edge =
        revisionEdgeStore
            .findByRunIdAndReviewerNodeId(runId, reviewerNodeId.toString())
            .orElseThrow(() -> new IllegalStateException("审查节点没有声明反思回边。"));
    if (!edge.getTargetNodeId().equals(targetNodeId.toString()))
      throw new IllegalStateException("反思回边目标与当前审查节点不一致。");
    if (edge.getRevisionCount() >= edge.getMaxRevisions())
      throw new IllegalStateException("反思次数已经达到上限。");
    if (!states.resetPairForRevision(
        runId, reviewerNodeId.toString(), targetNodeId.toString(), time))
      throw new IllegalStateException("反思节点没有可重置的已完成状态。");
    edge.trigger(verdictJson, time);
    revisionEdgeStore.save(edge);
  }

  /** 回边状态：节点标识、上限与最近一次结构化评审。 */
  public record RevisionEdgeState(
      UUID reviewerNodeId,
      UUID targetNodeId,
      int maxRevisions,
      int revisionCount,
      String lastVerdictJson) {}

  public WorkflowPlan workflowPlan(String runId) {
    return graphStore.findPlan(runId);
  }

  /**
   * 由已保存的动态计划与节点重建等价模板。
   *
   * <p>同任务迭代要让下一轮沿用上一轮那张受控图，而不是退回旧固定计划；模板标识、版本、授权边界与尝试上限全部 取自源计划本身，节点顺序与依赖按节点键还原。
   */
  public WorkflowTemplate templateOf(String runId) {
    WorkflowPlan plan = workflowPlan(runId);
    if (plan == null) throw new IllegalStateException("源运行没有动态计划，不能重建模板。");
    var nodes = find(runId);
    if (nodes.isEmpty()) throw new IllegalStateException("源运行没有可复制的计划节点，不能重建模板。");
    var keys = new java.util.LinkedHashMap<UUID, String>();
    for (var node : nodes) keys.put(node.id(), node.key());
    return new WorkflowTemplate(
        plan.templateId(),
        plan.templateVersion(),
        plan.scope(),
        plan.maxAttempts(),
        nodes.stream()
            .map(
                node ->
                    node.systemCheck()
                        ? WorkflowTemplate.Step.systemCheck(
                            node.key(),
                            node.label(),
                            node.phase(),
                            node.checkRef(),
                            node.dependencyIds().stream().map(keys::get).sorted().toList())
                        : new WorkflowTemplate.Step(
                            node.key(),
                            node.label(),
                            node.phase(),
                            node.definitionRef(),
                            node.dependencyIds().stream().map(keys::get).sorted().toList(),
                            node.budget()))
            .toList());
  }

  /**
   * 执行节点前读取规格，并按任务当前授权复核读写范围。
   *
   * <p>历史模板计划没有规格时返回 {@code null}，继续使用任务级文件范围。
   */
  public PlanNodeSpec requireNodeSpec(String runId, UUID nodeId, TaskDefinition task) {
    var specs = specsByNode(runId);
    if (specs.isEmpty()) return null;
    var spec = specs.get(nodeId);
    if (spec == null) throw new IllegalStateException("计划节点缺少文件范围规格。");
    if (!task.fileScopes().containsAll(spec.readFiles()))
      throw new IllegalStateException("节点读取范围超出任务当前授权文件。");
    if (!spec.readFiles().containsAll(spec.writeFiles()))
      throw new IllegalStateException("节点写入范围超出读取范围。");
    return spec;
  }

  public java.util.Map<UUID, PlanNodeSpec> specsByNode(String runId) {
    return views.specsByNode(runId);
  }

  /** 服务端重新计算调整后的图摘要，并继续执行写作节点的独立审查约束。 */
  public String adjustedGraphFingerprint(WorkflowPlan plan, List<PlanNode> nodes) {
    return adjustedGraphFingerprint(plan, nodes, List.of());
  }

  public String adjustedGraphFingerprint(
      WorkflowPlan plan, List<PlanNode> nodes, List<RevisionEdgeState> edges) {
    if (plan.scope().allowsWrite()) PlanExecutionGuard.requireIndependentReview(nodes);
    return PlanGraphFingerprint.fingerprint(
        nodes, plan.scope(), plan.authorizationSha256(), plan.maxAttempts(), Map.of(), Map.of(), edges);
  }

  /** 固定计划的摘要不含状态与时间，用于发现图、规格或装配引用发生意外变化。 */
  public List<PlanNode> checkedWorkflowNodes(String runId, WorkflowPlan plan) {
    return executionGuard.checkedWorkflowNodes(runId, plan, find(runId), bindings != null);
  }

  /** 恢复副本留存原计划来源；已核验结果只关联历史实例。 */
  public void attachRecovery(
      String runId, UUID checkpointId, java.util.Map<String, UUID> reused, String time) {
    if (graphStore.attachRecovery(runId, checkpointId, time) != 1)
      throw new IllegalStateException("无法关联工作流的恢复检查点。");
    for (var entry : reused.entrySet()) {
      if (!states.reuseNode(runId, entry.getKey(), entry.getValue().toString(), time))
        throw new IllegalStateException("无法关联已核验的历史节点结果。");
    }
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
    if (!states.claimCheck(runId, nodeId.toString(), time))
      throw new IllegalStateException("检查节点不存在、已启动或前置步骤尚未完成。");
  }

  /** 结算已认领的系统检查节点；失败状态与阻塞原因由调用方在同一事务中保存。 */
  public void finishCheckNode(String runId, UUID nodeId, PlanNodeStatus status, String time) {
    if (status != PlanNodeStatus.COMPLETED && status != PlanNodeStatus.FAILED)
      throw new IllegalArgumentException("系统检查节点只能结算为完成或失败。");
    if (!states.finishCheck(runId, nodeId.toString(), status, time))
      throw new IllegalStateException("没有可结算的系统检查节点。");
  }

  /** 旧角色用于定位接收节点；实际来源必须是图中已完成的直接前置节点。 */
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
    // 持久化层在认领时重验目标状态和依赖。
    if (!states.claimAgent(runId, target.id().toString(), agentId, time))
      throw new IllegalStateException("步骤不存在、已启动或前置步骤尚未完成。");
  }

  /** 从保存的图和节点状态取得全部就绪节点；调用方仍需事务认领并校验写集互斥。 */
  public List<PlanNode> readyNodes(List<PlanNode> nodes) {
    return PlanDependencyGraph.readyNodes(nodes);
  }

  /** 从保存的图和节点状态取得第一个就绪节点；调用方仍需事务认领。 */
  public PlanNode nextReady(List<PlanNode> nodes) {
    return readyNodes(nodes).stream().findFirst().orElse(null);
  }

  private static List<String> ready(List<PlanNode> nodes) {
    return PlanDependencyGraph.ready(nodes);
  }

  /** 复用已校验的历史分析，不伪造本运行中的 Agent 调用。 */
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

  /** 只重置当前节点指针；失败实例、尝试和交接仍可追溯。 */
  public void requeueFailedNode(String runId, UUID nodeId, String failedAgentId) {
    if (!states.requeueFailed(runId, nodeId.toString(), failedAgentId))
      throw new IllegalStateException("无法重新排队已失败的节点。");
  }

  /** 终止运行时保留已结束步骤；在途步骤结果未知，未启动的步骤标记跳过。 */
  public void settle(String runId, String time) {
    states.settle(runId, time);
  }

  /** 返回计划与真实保存的所有前置节点；异常关系不能被当作无依赖。 */
  public List<PlanNode> find(String runId) {
    return views.find(runId);
  }
}
