package dev.workbench.planning;

import dev.workbench.framework.agent.assembly.AgentAssemblySnapshot;
import dev.workbench.framework.workflow.plan.PlanNode;
import dev.workbench.framework.workflow.plan.PlanNodeSpec;
import dev.workbench.framework.workflow.plan.WorkflowTemplate;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 计划图摘要（v1 至 v8）：固定计划的摘要不含状态与时间，用于发现图、规格或装配引用发生意外变化。
 *
 * <p>摘要版本按已存在的持久化内容逐级降级：v1/v2 基础节点字段、v3 写入范围、v4 节点预算、v5 节点类型与系统检查引用、
 * v6 节点规格摘要、v7 Agent 装配绑定、v8 反思回边。历史计划必须逐字节保持原摘要，任何字段顺序调整都会让旧计划无法执行。
 */
final class PlanGraphFingerprint {
  private PlanGraphFingerprint() {}

  /** 基础摘要：只有节点字段与工作区授权。 */
  static String fingerprint(
      List<PlanNode> nodes, WorkflowTemplate.Scope scope, String authorization, int maxAttempts) {
    return fingerprint(nodes, scope, authorization, maxAttempts, Map.of());
  }

  /**
   * 候选计划使用 v6：在 v5 的节点字段后绑定每个节点的规格摘要。
   *
   * <p>规格包含读写文件、产出和验收条件，任何持久化改写都会使图摘要与计划不一致。
   */
  static String fingerprint(
      List<PlanNode> nodes,
      WorkflowTemplate.Scope scope,
      String authorization,
      int maxAttempts,
      Map<UUID, PlanNodeSpec> specs) {
    return fingerprint(nodes, scope, authorization, maxAttempts, specs, Map.of());
  }

  /**
   * 候选计划使用 v6/v7：v6 绑定节点规格，v7 额外绑定每个节点的 Agent 选择版本、来源和理由。
   *
   * <p>旧计划没有绑定记录时保持 v6 摘要，避免历史数据被重新解释。
   */
  static String fingerprint(
      List<PlanNode> nodes,
      WorkflowTemplate.Scope scope,
      String authorization,
      int maxAttempts,
      Map<UUID, PlanNodeSpec> specs,
      Map<UUID, String> bindings) {
    return fingerprint(nodes, scope, authorization, maxAttempts, specs, bindings, List.of());
  }

  /**
   * 有反思回边的计划使用 v8：在 v7 的节点、规格与装配绑定之后追加回边摘要。
   *
   * <p>没有回边的历史计划继续使用原降级链，摘要逐字节保持不变。
   */
  static String fingerprint(
      List<PlanNode> nodes,
      WorkflowTemplate.Scope scope,
      String authorization,
      int maxAttempts,
      Map<UUID, PlanNodeSpec> specs,
      Map<UUID, String> bindings,
      List<PlanService.RevisionEdgeState> revisionEdges) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      boolean workspaceGraph = scope.hasWorkspace();
      boolean hasBudgets = nodes.stream().anyMatch(node -> node.budget() != null);
      boolean hasChecks = nodes.stream().anyMatch(PlanNode::systemCheck);
      boolean hasSpecs = !specs.isEmpty();
      boolean hasBindings = !bindings.isEmpty();
      boolean hasRevisionEdges = !revisionEdges.isEmpty();
      addField(
          digest,
          hasRevisionEdges
              ? "workflow-graph-v8"
              : hasBindings
                  ? "workflow-graph-v7"
                  : hasSpecs
                      ? "workflow-graph-v6"
                      : hasChecks
                          ? "workflow-graph-v5"
                          : hasBudgets
                              ? "workflow-graph-v4"
                              : scope.allowsWrite()
                                  ? "workflow-graph-v3"
                                  : workspaceGraph ? "workflow-graph-v2" : "workflow-graph-v1");
      if (workspaceGraph) {
        addField(digest, scope.name());
        addField(digest, authorization);
      }
      for (var node : nodes) {
        if (hasChecks || hasSpecs) {
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
            addField(digest, field);
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
            addField(digest, field);
          }
        }
        if (workspaceGraph) addField(digest, Boolean.toString(node.workspaceRead()));
        if (scope.allowsWrite())
          addField(digest, Boolean.toString(node.workspaceWrite()));
        if (hasBudgets) {
          var limit = node.budget();
          addField(digest, limit == null ? "" : java.util.Objects.toString(limit.maxModelCalls(), ""));
          addField(digest, limit == null ? "" : java.util.Objects.toString(limit.maxToolCalls(), ""));
        }
        if (hasSpecs) {
          var spec = specs.get(node.id());
          addField(digest, spec == null ? "" : spec.sha256());
        }
        if (hasBindings) addField(digest, bindings.getOrDefault(node.id(), ""));
        for (UUID prerequisite : node.dependencyIds().stream().sorted().toList())
          addField(digest, prerequisite.toString());
      }
      if (hasRevisionEdges) {
        addField(digest, "revision-edges-v1");
        addField(digest, Integer.toString(revisionEdges.size()));
        for (var edge : revisionEdges.stream()
            .sorted(java.util.Comparator.comparing(item -> item.reviewerNodeId().toString()))
            .toList()) {
          addField(digest, edge.reviewerNodeId().toString());
          addField(digest, edge.targetNodeId().toString());
          addField(digest, Integer.toString(edge.maxRevisions()));
        }
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

  /** 先写 UTF-8 字节长度再写内容，避免相邻字段拼接产生歧义。 */
  private static void addField(MessageDigest digest, String field) {
    byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
    digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
    digest.update(bytes);
  }
}
