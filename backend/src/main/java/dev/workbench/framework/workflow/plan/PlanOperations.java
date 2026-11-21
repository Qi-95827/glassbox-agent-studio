package dev.workbench.framework.workflow.plan;

import dev.workbench.domain.AgentRole;
import dev.workbench.domain.InvocationStatus;
import dev.workbench.domain.TaskDefinition;
import java.util.List;
import java.util.UUID;

/** 运行存储所需的计划读写端口；具体计划服务由 planning 实现。 */
public interface PlanOperations {
  void initialize(String runId, boolean includeCoder);

  void initializeWorkflow(
      String runId,
      WorkflowTemplate template,
      TaskDefinition task,
      boolean reviewRequired,
      String time);

  void initializeFromCandidate(
      String runId, CandidateRunPlan candidatePlan, TaskDefinition task, String time);

  void start(String runId, String agentId, AgentRole role, String time);

  void requireDirectDependencySender(String runId, AgentRole receiver, UUID senderId);

  void reuseAnalysis(String runId, String sourceAgentId, String time);

  void finish(String agentId, InvocationStatus status, String time);

  void settle(String runId, String time);

  List<PlanNode> find(String runId);
}
