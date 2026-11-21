package dev.workbench.persistence.repository.view;

import java.util.Map;
import org.springframework.util.LinkedCaseInsensitiveMap;

public record ToolData(
    String id,
    String runId,
    String status,
    String createdAt,
    String updatedAt,
    String agentId,
    String toolName,
    String result,
    String errorMessage,
    Long durationMs,
    Integer toolVersion,
    String resourceVersion) {
  public Map<String, Object> apiFields() {
    var fields = new LinkedCaseInsensitiveMap<Object>();
    fields.put("ID", id);
    fields.put("RUN_ID", runId);
    fields.put("STATUS", status);
    fields.put("CREATED_AT", createdAt);
    fields.put("UPDATED_AT", updatedAt);
    fields.put("AGENT_ID", agentId);
    fields.put("TOOL_NAME", toolName);
    fields.put("RESULT", result);
    fields.put("ERROR_MESSAGE", errorMessage);
    fields.put("DURATION_MS", durationMs);
    fields.put("TOOL_VERSION", toolVersion);
    fields.put("RESOURCE_VERSION", resourceVersion);
    return fields;
  }
}
