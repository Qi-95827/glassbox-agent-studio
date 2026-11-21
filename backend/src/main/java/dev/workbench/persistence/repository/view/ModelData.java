package dev.workbench.persistence.repository.view;

import java.util.Map;
import org.springframework.util.LinkedCaseInsensitiveMap;

public record ModelData(
    String id,
    String runId,
    String status,
    String createdAt,
    String updatedAt,
    String agentId,
    String modelName,
    Integer inputTokens,
    Integer outputTokens,
    Long durationMs,
    String errorMessage) {
  public Map<String, Object> apiFields() {
    var fields = new LinkedCaseInsensitiveMap<Object>();
    fields.put("ID", id);
    fields.put("RUN_ID", runId);
    fields.put("STATUS", status);
    fields.put("CREATED_AT", createdAt);
    fields.put("UPDATED_AT", updatedAt);
    fields.put("AGENT_ID", agentId);
    fields.put("MODEL_NAME", modelName);
    fields.put("INPUT_TOKENS", inputTokens);
    fields.put("OUTPUT_TOKENS", outputTokens);
    fields.put("DURATION_MS", durationMs);
    fields.put("ERROR_MESSAGE", errorMessage);
    return fields;
  }
}
