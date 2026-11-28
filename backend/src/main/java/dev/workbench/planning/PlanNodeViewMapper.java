package dev.workbench.planning;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.workbench.domain.AgentRole;
import dev.workbench.framework.agent.assembly.AgentAssemblySnapshot;
import dev.workbench.framework.agent.definition.AgentDefinitionRef;
import dev.workbench.framework.workflow.check.SystemCheckRef;
import dev.workbench.framework.workflow.plan.AgentBindingInfo;
import dev.workbench.framework.workflow.plan.PlanNode;
import dev.workbench.framework.workflow.plan.PlanNodeKind;
import dev.workbench.framework.workflow.plan.PlanNodeSpec;
import dev.workbench.framework.workflow.plan.WorkflowNodeBudget;
import dev.workbench.persistence.entity.PlanNodeAgentBindingEntity;
import dev.workbench.persistence.repository.PlanNodeAgentBindingRepository;
import dev.workbench.persistence.repository.PlanNodeSpecRepository;
import dev.workbench.persistence.repository.PlanRevisionEdgeRepository;
import dev.workbench.persistence.repository.PlanStateRepository;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 计划持久化行到领域视图的映射：节点依赖图、节点规格、Agent 装配绑定与反思回边。
 *
 * <p>读取时会校验依赖边必须落在同一运行与同一计划版本内；写入方向的 {@link #writeJson} 仅服务于同包物化器。
 */
final class PlanNodeViewMapper {
  private final PlanStateRepository states;
  private final PlanNodeSpecRepository specStore;
  private final PlanNodeAgentBindingRepository bindings;
  private final PlanRevisionEdgeRepository revisionEdgeStore;
  private final ObjectMapper json;

  PlanNodeViewMapper(
      PlanStateRepository states,
      PlanNodeSpecRepository specStore,
      PlanNodeAgentBindingRepository bindings,
      PlanRevisionEdgeRepository revisionEdgeStore,
      ObjectMapper json) {
    this.states = states;
    this.specStore = specStore;
    this.bindings = bindings;
    this.revisionEdgeStore = revisionEdgeStore;
    this.json = json;
  }

  /** 返回计划与真实保存的所有前置节点；异常关系不能被当作无依赖。 */
  List<PlanNode> find(String runId) {
    List<PlanNode> rows =
        states.findNodes(runId).stream().map(PlanNodeViewMapper::readNode).toList();

    if (rows.isEmpty()) return List.of();
    var bindingRows = new LinkedHashMap<UUID, PlanNodeAgentBindingEntity>();
    if (bindings != null)
      for (var row : bindings.findByRunIdOrderByNodeId(runId))
        bindingRows.put(UUID.fromString(row.getNodeId()), row);
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
              row.checkRef(),
              bindingInfo(bindingRows.get(row.id()))));
    }
    PlanDependencyGraph.validate(result);
    return List.copyOf(result);
  }

  /** 按节点标识读取持久化规格；历史模板计划没有规格时返回空表。 */
  Map<UUID, PlanNodeSpec> specsByNode(String runId) {
    var result = new LinkedHashMap<UUID, PlanNodeSpec>();
    for (var row : specStore.findByRunId(runId)) {
      var spec =
          new PlanNodeSpec(
              UUID.fromString(row.getPlanNodeId()),
              row.getCandidateSha256(),
              row.getNodeKey(),
              readList(row.getReadFilesJson()),
              readList(row.getWriteFilesJson()),
              readOutputs(row.getOutputsJson()),
              readList(row.getAcceptanceJson()),
              row.getSpecSha256());
      result.put(spec.nodeId(), spec);
    }
    return result;
  }

  /** 每个节点的 Agent 装配摘要；旧计划没有绑定记录时返回空表。 */
  Map<UUID, String> bindingsByNode(String runId) {
    if (bindings == null) return Map.of();
    var result = new LinkedHashMap<UUID, String>();
    for (var row : bindings.findByRunIdOrderByNodeId(runId))
      result.put(
          UUID.fromString(row.getNodeId()),
          AgentAssemblySnapshot.sha256(
              String.join(
                  "\n",
                  row.getAgentKind(),
                  row.getDefinitionId(),
                  Integer.toString(row.getDefinitionVersion()),
                  java.util.Objects.toString(row.getSpecSha256(), ""),
                  row.getSource(),
                  row.getReason(),
                  row.getDisplayName())));
    return result;
  }

  /** 运行中持久化的反思回边；历史计划没有记录时返回空。 */
  List<PlanService.RevisionEdgeState> revisionEdges(String runId) {
    if (revisionEdgeStore == null) return List.of();
    return revisionEdgeStore.findByRunIdOrderByReviewerNodeIdAsc(runId).stream()
        .map(
            row ->
                new PlanService.RevisionEdgeState(
                    UUID.fromString(row.getReviewerNodeId()),
                    UUID.fromString(row.getTargetNodeId()),
                    row.getMaxRevisions(),
                    row.getRevisionCount(),
                    row.getLastVerdictJson()))
        .toList();
  }

  /** 计划规格与绑定快照的 JSON 编码；编码失败按不可恢复的计划错误处理。 */
  String writeJson(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("节点规格无法编码。", e);
    }
  }

  /** 单行节点记录到视图；类型、引用与摘要不完整时立即失败，绝不猜测。 */
  static PlanNode readNode(PlanStateRepository.NodeRow row) {
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

  /** 节点上的 Agent 装配快照；没有绑定记录时返回 {@code null}。 */
  static AgentBindingInfo bindingInfo(PlanNodeAgentBindingEntity row) {
    if (row == null) return null;
    return new AgentBindingInfo(
        row.getAgentKind(),
        row.getSource(),
        row.getReason(),
        row.getDisplayName(),
        row.getSpecSha256(),
        row.getComponentsJson());
  }

  private static UUID parseUuid(String value) {
    return value == null ? null : UUID.fromString(value);
  }

  private List<String> readList(String value) {
    try {
      return json.readValue(
          value, json.getTypeFactory().constructCollectionType(List.class, String.class));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("节点规格文件范围无法读取。", e);
    }
  }

  private List<PlanNodeSpec.Output> readOutputs(String value) {
    try {
      return json.readValue(
          value,
          json.getTypeFactory().constructCollectionType(List.class, PlanNodeSpec.Output.class));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("节点规格产出无法读取。", e);
    }
  }

  /** 持久化依赖边；运行归属用于校验跨运行引用。 */
  private record Dependency(String runId, UUID nodeId, UUID prerequisiteId) {}
}
