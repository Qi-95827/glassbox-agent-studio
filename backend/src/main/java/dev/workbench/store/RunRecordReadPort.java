package dev.workbench.store;

import dev.workbench.domain.*;
import dev.workbench.framework.agent.assembly.AgentAssemblySnapshot;
import dev.workbench.framework.agent.definition.AgentDefinitionRef;
import dev.workbench.framework.skill.SkillAssignment;
import dev.workbench.framework.skill.SkillRef;
import dev.workbench.framework.tool.AgentToolGrant;
import dev.workbench.framework.workflow.plan.CandidateRunPlan;
import dev.workbench.framework.workflow.plan.WorkflowTemplate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** 任务、Agent 装配与调用证据的只读委托；RunRecordService 通过实现该端口保留公开 API。 */
public interface RunRecordReadPort
    extends dev.workbench.framework.control.RunControlStore,
        dev.workbench.framework.workflow.plan.PlanningRunReader {
  TaskRecordService taskStore();

  AgentAssemblyRecordService agentStore();

  RunEvidenceRecordService evidenceStore();

  default List<Map<String, Object>> tasks() {
    return taskStore().tasks();
  }

  default Map<String, Object> task(String id) {
    return taskStore().task(id);
  }

  default void setTaskArchived(String taskId, boolean archived) {
    taskStore().setTaskArchived(taskId, archived);
  }

  default int taskVersion(String taskId) {
    return taskStore().taskVersion(taskId);
  }

  default TaskDefinition taskDefinition(String id) {
    return taskStore().taskDefinition(id);
  }

  default Map<AgentRole, RoleBudget> roleBudgets(String taskId) {
    return taskStore().roleBudgets(taskId);
  }

  default Map<ToolKind, Integer> toolBudgets(String taskId) {
    return taskStore().toolBudgets(taskId);
  }

  default AgentRole agentRole(String runId, String agentId) {
    return taskStore().agentRole(runId, agentId);
  }

  default CheckpointSnapshot checkedCheckpoint(String id) {
    return evidenceStore().checkedCheckpoint(id);
  }

  default List<Map<String, Object>> events(String id) {
    return evidenceStore().events(id);
  }

  default List<Map<String, Object>> agents(String runId) {
    return agentStore().agents(runId);
  }

  default RunRecordService.PlannedAgentDefinition plannedDefinitionForAgent(String runId, String agentId) {
    var definition = agentStore().plannedDefinitionForAgent(runId, agentId);
    return new RunRecordService.PlannedAgentDefinition(definition.reference(), definition.sha256());
  }

  default void recordAgentAssembly(String runId, String agentId, AgentAssemblySnapshot snapshot) {
    agentStore().recordAgentAssembly(runId, agentId, snapshot);
  }

  default List<AgentAssemblySnapshot.Recorded> agentAssemblies(String runId) {
    return agentStore().agentAssemblies(runId);
  }

  default void recordAgentDefinition(
      String runId, String agentId, AgentDefinitionRef ref, String sha256) {
    agentStore().recordAgentDefinition(runId, agentId, ref, sha256);
  }

  default void recordAgentSkills(String agentId, List<SkillRef> skills) {
    agentStore().recordAgentSkills(agentId, skills);
  }

  default List<SkillAssignment> skills(String runId) {
    return agentStore().skills(runId);
  }

  default void recordAgentToolGrants(String agentId, java.util.Set<ToolKind> tools) {
    agentStore().recordAgentToolGrants(agentId, tools);
  }

  default void recordAgentVersionedToolGrants(
      String agentId, java.util.Map<ToolKind, Integer> tools) {
    agentStore().recordAgentVersionedToolGrants(agentId, tools);
  }

  default List<AgentToolGrant> agentToolGrants(String runId) {
    return agentStore().agentToolGrants(runId);
  }

  default List<Map<String, Object>> tools(String runId) {
    return agentStore().tools(runId);
  }

  default List<Map<String, Object>> models(String runId) {
    return agentStore().models(runId);
  }

  default void startModel(String id, String runId, String agentId, ModelKind model) {
    agentStore().startModel(id, runId, agentId, model);
  }

  default void finishModel(
      String id,
      InvocationStatus status,
      Integer input,
      Integer output,
      String error,
      long duration) {
    agentStore().finishModel(id, status, input, output, error, duration);
  }

  default void startTool(String id, String runId, String agentId, String toolName) {
    agentStore().startTool(id, runId, agentId, toolName);
  }

  default void startTool(String id, String runId, String agentId, String toolName, Integer version) {
    agentStore().startTool(id, runId, agentId, toolName, version);
  }

  default void startTool(
      String id, String runId, String agentId, ToolKind kind, String fingerprint) {
    agentStore().startTool(id, runId, agentId, kind, fingerprint);
  }

  default void startTool(
      String id, String runId, String agentId, ToolKind kind, Integer version, String fingerprint) {
    agentStore().startTool(id, runId, agentId, kind, version, fingerprint);
  }

  default void finishTool(
      String id, InvocationStatus status, String result, String error, long duration) {
    agentStore().finishTool(id, status, result, error, duration);
  }

  default void finishTool(
      String id,
      InvocationStatus status,
      String result,
      String error,
      long duration,
      String resourceVersion) {
    agentStore().finishTool(id, status, result, error, duration, resourceVersion);
  }

  default void handoff(HandoffDirection direction, Handoff payload) {
    evidenceStore().handoff(direction, payload);
  }

  default List<Map<String, Object>> handoffs(String runId) {
    return evidenceStore().handoffs(runId);
  }

  default List<Long> toolEvidence(String runId) {
    return evidenceStore().toolEvidence(runId);
  }

  default void startAgent(String id, String runId, String goal, AgentRole role) {
    agentStore().startAgent(id, runId, goal, role);
  }

  default void startAgentWithDelegation(String id, String runId, String goal, Handoff delegation) {
    agentStore().startAgentWithDelegation(id, runId, goal, delegation);
  }

  default void startAgentFromHandoff(
      String id, String runId, String goal, AgentRole role, UUID handoffId) {
    agentStore().startAgentFromHandoff(id, runId, goal, role, handoffId);
  }

  default void insertAgent(String id, String runId, String goal, AgentRole role, String time) {
    agentStore().insertAgent(id, runId, goal, role, time);
  }

  default void finishAgent(String id, InvocationStatus status, String result) {
    agentStore().finishAgent(id, status, result);
  }

  default void finishAgentWithResult(String id, String result, Handoff resultHandoff) {
    agentStore().finishAgentWithResult(id, result, resultHandoff);
  }

  default List<Map<String, Object>> checkpoints(String id) {
    return evidenceStore().checkpoints(id);
  }

  default Map<String, Object> checkpoint(String id) {
    return evidenceStore().checkpoint(id);
  }

  default int countAgentModelCalls(String agentId) {
    return evidenceStore().countAgentModelCalls(agentId);
  }

  default int countAgentToolCalls(String agentId) {
    return evidenceStore().countAgentToolCalls(agentId);
  }

  default int countNodeModelCalls(String runId, UUID nodeId) {
    return evidenceStore().countNodeModelCalls(runId, nodeId);
  }

  default int countNodeToolCalls(String runId, UUID nodeId) {
    return evidenceStore().countNodeToolCalls(runId, nodeId);
  }

  default int consecutiveToolAttempts(String runId, ToolKind kind, String fingerprint, int limit) {
    return evidenceStore().consecutiveToolAttempts(runId, kind, fingerprint, limit);
  }

  default dev.workbench.framework.control.RunControlStore.ReportedTokenUsage reportedTokens(
      String runId) {
    return evidenceStore().reportedTokens(runId);
  }

  default int countToolCalls(String runId, ToolKind kind) {
    return evidenceStore().countToolCalls(runId, kind);
  }

  default void checkpointSkipped(String runId, CheckpointSkipReason reason) {
    evidenceStore().checkpointSkipped(runId, reason);
  }

  default void event(String runId, EventType type, String message) {
    evidenceStore().event(runId, type, message);
  }

  default void event(String runId, EventType type, String message, EventRef ref) {
    evidenceStore().event(runId, type, message, ref);
  }

  default EventRef eventRefForAgent(String runId, String agentId) {
    return evidenceStore().eventRefForAgent(runId, agentId);
  }

  default WorkspaceReviewCheckpoint workspaceReviewPayload(String value) {
    return evidenceStore().workspaceReviewPayload(value);
  }

  default String analysisReference(String runId, String agentId) {
    return evidenceStore().analysisReference(runId, agentId);
  }

  default RunRecordService.AnalysisSource analysisSource(String runId, String reference) {
    var source = evidenceStore().analysisSource(runId, reference);
    return new RunRecordService.AnalysisSource(source.agentId(), source.result());
  }

  default RunRecordService.CheckpointAnalysisSource checkpointAnalysisSource(
      String checkpointRunId, String reference) {
    var source = evidenceStore().checkpointAnalysisSource(checkpointRunId, reference);
    return new RunRecordService.CheckpointAnalysisSource(source.runId(), source.agentId(), source.result());
  }

  default String ancestorAnalysisReference(
      String checkpointRunId, String sourceRunId, UUID sourceAgentId) {
    return evidenceStore().ancestorAnalysisReference(checkpointRunId, sourceRunId, sourceAgentId);
  }

  default void reuseAnalysis(String runId, UUID sourceAgentId) {
    evidenceStore().reuseAnalysis(runId, sourceAgentId);
  }

  default boolean validCheckpoint(Map<String, Object> cp) {
    return evidenceStore().validCheckpoint(cp);
  }

}
