package dev.workbench.framework.control;

import dev.workbench.domain.AgentRole;
import dev.workbench.domain.EventType;
import dev.workbench.domain.InvocationStatus;
import dev.workbench.domain.ModelKind;
import dev.workbench.domain.RunStatus;
import dev.workbench.domain.ToolKind;
import java.util.UUID;

/** RunControl 需要的最小持久化端口；具体存储由任务/证据模块实现。 */
public interface RunControlStore {
  void update(
      String runId,
      RunStatus status,
      String reason,
      String result,
      int rounds,
      int tools,
      long elapsedMs);

  void event(String runId, EventType type, String message);

  int countAgentModelCalls(String agentId);

  int countNodeModelCalls(String runId, UUID nodeId);

  ReportedTokenUsage reportedTokens(String runId);

  int countNodeToolCalls(String runId, UUID nodeId);

  int countAgentToolCalls(String agentId);

  int countToolCalls(String runId, ToolKind kind);

  int consecutiveToolAttempts(String runId, ToolKind kind, String fingerprint, int limit);

  AgentRole agentRole(String runId, String agentId);

  void startTool(String id, String runId, String agentId, String toolName, Integer version);

  void startTool(
      String id, String runId, String agentId, ToolKind kind, Integer version, String fingerprint);

  void finishTool(
      String id,
      InvocationStatus status,
      String result,
      String error,
      long duration,
      String resourceVersion);

  void startModel(String id, String runId, String agentId, ModelKind model);

  void finishModel(
      String id,
      InvocationStatus status,
      Integer input,
      Integer output,
      String error,
      long duration);

  record ReportedTokenUsage(long knownTokens, long unknownCalls) {}
}
