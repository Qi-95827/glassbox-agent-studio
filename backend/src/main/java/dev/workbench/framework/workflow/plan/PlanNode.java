package dev.workbench.framework.workflow.plan;

import dev.workbench.domain.AgentRole;
import dev.workbench.framework.agent.definition.AgentDefinitionRef;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 持久化步骤的只读视图；前置角色、Agent 和时间在相应阶段尚未发生时为空。 */
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
    String definitionSha256) {
  public PlanNode {
    dependencyIds = List.copyOf(dependencyIds);
  }
}
