package dev.workbench.framework.workflow.plan;

import dev.workbench.domain.AgentRole;
import dev.workbench.framework.agent.definition.AgentDefinitionRef;
import dev.workbench.framework.workflow.check.SystemCheckRef;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 持久化步骤的只读视图；前置角色、Agent 和时间在相应阶段尚未发生时为空。
 *
 * <p>Agent 节点使用 {@code definitionRef} 和 {@code definitionSha256}；系统检查节点只使用 {@code
 * checkRef}，其角色、Agent 实例和定义均为空，避免把检查记录伪装成 Agent 调用。
 */
public record PlanNode(
    UUID id,
    UUID runId,
    int sequence,
    AgentRole role,
    AgentRole prerequisite,
    PlanNodeStatus status,
    UUID agentId,
    UUID sourceAgentId,
    Instant startedAt,
    Instant finishedAt,
    List<UUID> dependencyIds,
    AgentDefinitionRef definitionRef,
    String definitionSha256,
    UUID planVersionId,
    String key,
    String label,
    String phase,
    boolean workspaceRead,
    boolean workspaceWrite,
    WorkflowNodeBudget budget,
    PlanNodeKind kind,
    SystemCheckRef checkRef) {
  public PlanNode {
    dependencyIds = List.copyOf(dependencyIds);
    if (kind == null) throw new IllegalArgumentException("计划节点类型不能为空。");
    if (kind == PlanNodeKind.AGENT && planVersionId != null && definitionRef == null)
      throw new IllegalArgumentException("Agent 节点缺少定义引用。");
    if (kind == PlanNodeKind.SYSTEM_CHECK && (checkRef == null || definitionRef != null))
      throw new IllegalArgumentException("系统检查节点必须且只能引用受信检查。");
  }

  /** 是否由系统检查执行器处理。 */
  public boolean systemCheck() {
    return kind == PlanNodeKind.SYSTEM_CHECK;
  }
}
