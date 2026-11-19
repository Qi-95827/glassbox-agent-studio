package dev.workbench.persistence.repository;

import dev.workbench.domain.RunSnapshot;
import dev.workbench.domain.RunStatus;
import java.util.Map;
import java.util.UUID;
import org.springframework.util.LinkedCaseInsensitiveMap;

/** 运行详情投影，保留既有 API 字段和历史空值。 */
public record RunData(
    String id,
    String taskId,
    String parentId,
    String status,
    String reason,
    String result,
    Integer rounds,
    Integer tools,
    Integer maxRounds,
    Integer maxTools,
    Integer maxSeconds,
    Long elapsedMs,
    String createdAt,
    String updatedAt,
    Integer maxPerTool,
    Long maxReportedTokens,
    Integer maxIdenticalTools,
    Integer maxAgentRounds,
    Integer maxAgentTools,
    String recoveryDispatch,
    String stopCause) {
  public RunSnapshot snapshot() {
    return new RunSnapshot(
        UUID.fromString(id),
        UUID.fromString(taskId),
        parentId == null ? null : UUID.fromString(parentId),
        RunStatus.valueOf(status),
        rounds,
        tools,
        maxRounds,
        maxTools,
        maxPerTool,
        maxReportedTokens,
        maxIdenticalTools,
        maxSeconds,
        elapsedMs,
        maxAgentRounds,
        maxAgentTools);
  }

  public Map<String, Object> apiFields() {
    var fields = new LinkedCaseInsensitiveMap<Object>();
    fields.put("ID", id);
    fields.put("TASK_ID", taskId);
    fields.put("PARENT_ID", parentId);
    fields.put("STATUS", status);
    fields.put("REASON", reason);
    fields.put("RESULT", result);
    fields.put("ROUNDS", rounds);
    fields.put("TOOLS", tools);
    fields.put("MAX_ROUNDS", maxRounds);
    fields.put("MAX_TOOLS", maxTools);
    fields.put("MAX_SECONDS", maxSeconds);
    fields.put("ELAPSED_MS", elapsedMs);
    fields.put("CREATED_AT", createdAt);
    fields.put("UPDATED_AT", updatedAt);
    fields.put("MAX_PER_TOOL", maxPerTool);
    fields.put("MAX_REPORTED_TOKENS", maxReportedTokens);
    fields.put("MAX_IDENTICAL_TOOLS", maxIdenticalTools);
    fields.put("MAX_AGENT_ROUNDS", maxAgentRounds);
    fields.put("MAX_AGENT_TOOLS", maxAgentTools);
    fields.put("RECOVERY_DISPATCH", recoveryDispatch);
    fields.put("STOP_CAUSE", stopCause);
    return fields;
  }
}