package dev.workbench.persistence.repository.view;

import java.util.Map;
import org.springframework.util.LinkedCaseInsensitiveMap;

public record AgentData(
    String id,
    String runId,
    String status,
    String createdAt,
    String updatedAt,
    String roleName,
    String taskGoal,
    String result,
    String roleCode,
    Integer roleVersion,
    String definitionId,
    Integer definitionVersion,
    String definitionSha256) {
  public Map<String, Object> apiFields() {
    var fields = new LinkedCaseInsensitiveMap<Object>();
    fields.put("ID", id);
    fields.put("RUN_ID", runId);
    fields.put("STATUS", status);
    fields.put("CREATED_AT", createdAt);
    fields.put("UPDATED_AT", updatedAt);
    fields.put("ROLE_NAME", roleName);
    fields.put("TASK_GOAL", taskGoal);
    fields.put("RESULT", result);
    fields.put("ROLE_CODE", roleCode);
    fields.put("ROLE_VERSION", roleVersion);
    fields.put("DEFINITION_ID", definitionId);
    fields.put("DEFINITION_VERSION", definitionVersion);
    fields.put("DEFINITION_SHA256", definitionSha256);
    return fields;
  }
}
