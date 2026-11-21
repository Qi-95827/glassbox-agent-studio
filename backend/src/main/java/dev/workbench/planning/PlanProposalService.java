package dev.workbench.planning;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.workbench.framework.workflow.plan.PlanNode;
import dev.workbench.framework.workflow.plan.WorkflowNodeBudget;
import dev.workbench.framework.workflow.plan.WorkflowPlan;
import dev.workbench.persistence.entity.PlanAdjustmentEntity;
import dev.workbench.persistence.repository.PlanAdjustmentRepository;
import dev.workbench.persistence.repository.PlanGraphRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import dev.workbench.store.RunRecordService;

/**
 * 待确认计划的候选调整。
 *
 * <p>候选只能修改节点预算和依赖关系；节点身份、Agent 定义、系统检查、工作区权限和模板授权不可改变。每次调整先完整校验，再由用户应用；应用后仍必须走原计划批准入口。
 */
@Service
public class PlanProposalService {
  private final PlanService plans;
  private final PlanAdjustmentRepository adjustments;
  private final PlanGraphRepository graphStore;
  private final RunRecordService runs;
  private final ObjectMapper json;

  public PlanProposalService(
      PlanService plans,
      PlanAdjustmentRepository adjustments,
      PlanGraphRepository graphStore,
      RunRecordService runs,
      ObjectMapper json) {
    this.plans = plans;
    this.adjustments = adjustments;
    this.graphStore = graphStore;
    this.runs = runs;
    this.json = json;
  }

  @Transactional
  public Proposal propose(String runId, List<NodeChange> changes, String expectedBaseSha) {
    WorkflowPlan plan = pendingPlan(runId);
    if (expectedBaseSha == null || !expectedBaseSha.equals(plan.graphSha256()))
      throw new IllegalStateException("计划版本已变化，请重新读取后再调整。");
    var nodes = plans.find(runId);
    List<NodeChange> normalized = validate(runId, plan, nodes, changes);
    String proposalJson;
    try {
      proposalJson = json.writeValueAsString(normalized);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("候选计划无法保存。", e);
    }
    String proposalSha = sha(proposalJson);
    int version =
        adjustments
            .findFirstByRunIdOrderByVersionDesc(runId)
            .map(row -> row.getVersion() + 1)
            .orElse(1);
    PlanAdjustmentEntity saved =
        adjustments.save(
            new PlanAdjustmentEntity(
                dev.workbench.domain.WorkbenchIds.newId(),
                runId,
                plan.id().toString(),
                version,
                plan.graphSha256(),
                proposalJson,
                proposalSha,
                "PROPOSED",
                Instant.now().toString()));
    return toProposal(saved, diff(nodes, normalized));
  }

  /** 应用候选：再次核对基线摘要和完整规则，再更新预算、依赖与图摘要。 */
  @Transactional
  public Applied apply(String runId, String adjustmentId) {
    WorkflowPlan plan = pendingPlan(runId);
    PlanAdjustmentEntity row =
        adjustments
            .findByIdAndRunId(adjustmentId, runId)
            .orElseThrow(() -> new IllegalArgumentException("候选计划不存在。"));
    if (!"PROPOSED".equals(row.getStatus())) throw new IllegalStateException("候选计划已经应用或失效。");
    if (!plan.graphSha256().equals(row.getBaseSha256()))
      throw new IllegalStateException("计划已变化，候选调整基于旧版本，不能应用。");
    var nodes = plans.find(runId);
    List<NodeChange> changes = read(row.getProposalJson());
    List<NodeChange> normalized = validate(runId, plan, nodes, changes);
    Map<String, String> ids = new LinkedHashMap<>();
    Map<String, Integer> modelBudgets = new LinkedHashMap<>();
    Map<String, Integer> toolBudgets = new LinkedHashMap<>();
    Map<String, List<String>> dependencies = new LinkedHashMap<>();
    for (var node : nodes) ids.put(node.key(), node.id().toString());
    for (var change : normalized) {
      modelBudgets.put(change.key(), change.maxModelCalls());
      toolBudgets.put(change.key(), change.maxToolCalls());
      dependencies.put(change.key(), change.dependencies());
    }
    graphStore.applyAdjustment(
        plan.id().toString(),
        runId,
        nodes.stream().map(PlanNode::key).toList(),
        modelBudgets,
        toolBudgets,
        dependencies,
        ids);
    String graphSha = plans.adjustedGraphFingerprint(plan, plans.find(runId));
    graphStore.updateGraphSha256(plan.id().toString(), graphSha);
    row.apply(Instant.now().toString());
    adjustments.save(row);
    return new Applied(adjustmentId, graphSha, diff(nodes, normalized));
  }

  public List<Proposal> list(String runId) {
    WorkflowPlan plan = plans.workflowPlan(runId);
    if (plan == null) return List.of();
    var base = plans.find(runId);
    return adjustments.findByRunIdOrderByVersionAsc(runId).stream()
        .map(row -> toProposal(row, diff(base, read(row.getProposalJson()))))
        .toList();
  }

  /** 最新候选与当前已保存计划的字段差异，供确认页展示。 */
  public List<Change> latestDiff(String runId) {
    WorkflowPlan plan = plans.workflowPlan(runId);
    if (plan == null) return List.of();
    return adjustments
        .findFirstByRunIdOrderByVersionDesc(runId)
        .map(row -> diff(plans.find(runId), read(row.getProposalJson())))
        .orElse(List.of());
  }

  private WorkflowPlan pendingPlan(String runId) {
    runs.runSnapshot(runId);
    WorkflowPlan plan = plans.workflowPlan(runId);
    if (plan == null) throw new IllegalArgumentException("该运行没有动态计划。");
    if (!plan.reviewRequired() || plan.approvedAt() != null)
      throw new IllegalStateException("只有待确认的计划可以调整。");
    return plan;
  }

  private List<NodeChange> validate(
      String runId, WorkflowPlan plan, List<PlanNode> nodes, List<NodeChange> changes) {
    if (changes == null || changes.isEmpty()) throw new IllegalArgumentException("候选计划至少需要包含一个节点。");
    var byKey = new LinkedHashMap<String, PlanNode>();
    for (var node : nodes) byKey.put(node.key(), node);
    if (changes.size() != byKey.size())
      throw new IllegalArgumentException("候选计划必须完整包含现有节点，不能新增或删除节点。");
    var seen = new HashSet<String>();
    for (var change : changes) {
      if (change == null || change.key() == null || !byKey.containsKey(change.key()))
        throw new IllegalArgumentException("候选计划包含未知节点。");
      if (!seen.add(change.key())) throw new IllegalArgumentException("候选计划节点重复。");
      if (change.dependencies() == null) throw new IllegalArgumentException("节点依赖不能为空；无依赖请传空列表。");
      if (new HashSet<>(change.dependencies()).size() != change.dependencies().size())
        throw new IllegalArgumentException("节点依赖不能重复。");
      for (String dependency : change.dependencies()) {
        if (dependency == null || !byKey.containsKey(dependency))
          throw new IllegalArgumentException("依赖节点不在当前计划中：" + dependency);
        if (dependency.equals(change.key())) throw new IllegalArgumentException("节点不能依赖自身。");
      }
      if (change.maxModelCalls() != null || change.maxToolCalls() != null)
        new WorkflowNodeBudget(change.maxModelCalls(), change.maxToolCalls());
      var run = runs.runSnapshot(runId);
      if (change.maxModelCalls() != null
          && run.maxRounds() != null
          && change.maxModelCalls() > run.maxRounds())
        throw new IllegalArgumentException("节点模型调用上限不能超过运行轮次上限。");
      if (change.maxToolCalls() != null
          && run.maxTools() != null
          && change.maxToolCalls() > run.maxTools())
        throw new IllegalArgumentException("节点工具调用上限不能超过运行工具上限。");
    }
    if (!seen.equals(byKey.keySet())) throw new IllegalArgumentException("候选计划必须覆盖全部现有节点。");
    // 依赖必须构成有向无环图，避免候选计划在保存后无法调度。
    var graph = new LinkedHashMap<String, List<String>>();
    for (var change : changes) graph.put(change.key(), change.dependencies());
    var visiting = new HashSet<String>();
    var visited = new HashSet<String>();
    for (String key : graph.keySet()) requireAcyclic(key, graph, visiting, visited);
    // 按候选依赖构造只读节点，复用原计划校验独立审查与图摘要。
    var adjusted =
        nodes.stream()
            .map(
                node -> {
                  var change =
                      changes.stream()
                          .filter(item -> item.key().equals(node.key()))
                          .findFirst()
                          .orElseThrow();
                  return withChange(node, change, byKey);
                })
            .toList();
    plans.adjustedGraphFingerprint(plan, adjusted);
    return List.copyOf(changes);
  }

  private static void requireAcyclic(
      String key, Map<String, List<String>> graph, Set<String> visiting, Set<String> visited) {
    if (visited.contains(key)) return;
    if (!visiting.add(key)) throw new IllegalArgumentException("候选计划依赖存在环。");
    for (String dependency : graph.getOrDefault(key, List.of()))
      requireAcyclic(dependency, graph, visiting, visited);
    visiting.remove(key);
    visited.add(key);
  }

  private static PlanNode withChange(
      PlanNode node, NodeChange change, Map<String, PlanNode> byKey) {
    var dependencyIds = change.dependencies().stream().map(key -> byKey.get(key).id()).toList();
    return new PlanNode(
        node.id(),
        node.runId(),
        node.sequence(),
        node.role(),
        node.prerequisite(),
        node.status(),
        node.agentId(),
        node.sourceAgentId(),
        node.startedAt(),
        node.finishedAt(),
        dependencyIds,
        node.definitionRef(),
        node.definitionSha256(),
        node.planVersionId(),
        node.key(),
        node.label(),
        node.phase(),
        node.workspaceRead(),
        node.workspaceWrite(),
        change.maxModelCalls() == null && change.maxToolCalls() == null
            ? null
            : new WorkflowNodeBudget(change.maxModelCalls(), change.maxToolCalls()),
        node.kind(),
        node.checkRef());
  }

  private List<Change> diff(List<PlanNode> base, List<NodeChange> changes) {
    var byKey = new LinkedHashMap<String, PlanNode>();
    for (var node : base) byKey.put(node.key(), node);
    var result = new ArrayList<Change>();
    for (var change : changes) {
      var node = byKey.get(change.key());
      if (node == null) continue;
      Integer beforeModel = node.budget() == null ? null : node.budget().maxModelCalls();
      Integer beforeTools = node.budget() == null ? null : node.budget().maxToolCalls();
      Map<UUID, String> keyById = new LinkedHashMap<>();
      for (var candidate : base) keyById.put(candidate.id(), candidate.key());
      List<String> beforeDeps = node.dependencyIds().stream().map(keyById::get).sorted().toList();
      List<String> afterDeps = change.dependencies().stream().sorted().toList();
      if (java.util.Objects.equals(beforeModel, change.maxModelCalls())
          && java.util.Objects.equals(beforeTools, change.maxToolCalls())
          && beforeDeps.equals(afterDeps)) continue;
      result.add(
          new Change(
              change.key(),
              node.label(),
              beforeModel,
              change.maxModelCalls(),
              beforeTools,
              change.maxToolCalls(),
              afterDeps.stream().filter(item -> !beforeDeps.contains(item)).toList(),
              beforeDeps.stream().filter(item -> !afterDeps.contains(item)).toList()));
    }
    return List.copyOf(result);
  }

  private Proposal toProposal(PlanAdjustmentEntity row, List<Change> diff) {
    return new Proposal(
        row.getId(),
        row.getRunId(),
        row.getPlanVersionId(),
        row.getVersion(),
        row.getBaseSha256(),
        row.getProposalSha256(),
        row.getStatus(),
        row.getCreatedAt(),
        diff);
  }

  private List<NodeChange> read(String value) {
    try {
      return json.readValue(
          value, json.getTypeFactory().constructCollectionType(List.class, NodeChange.class));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("已保存的候选计划无法读取。", e);
    }
  }

  private static String sha(String text) {
    try {
      return java.util.HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  public record NodeChange(
      String key, Integer maxModelCalls, Integer maxToolCalls, List<String> dependencies) {}

  public record Change(
      String key,
      String label,
      Integer beforeMaxModelCalls,
      Integer afterMaxModelCalls,
      Integer beforeMaxToolCalls,
      Integer afterMaxToolCalls,
      List<String> addedDependencies,
      List<String> removedDependencies) {}

  public record Proposal(
      String id,
      String runId,
      String planVersionId,
      int version,
      String baseSha256,
      String proposalSha256,
      String status,
      String createdAt,
      List<Change> diff) {}

  public record Applied(String adjustmentId, String graphSha256, List<Change> diff) {}
}
