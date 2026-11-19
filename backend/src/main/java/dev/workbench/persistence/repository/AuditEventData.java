package dev.workbench.persistence.repository;

import java.util.Map;
import org.springframework.util.LinkedCaseInsensitiveMap;

/** 审计记录投影。 */
public record AuditEventData(
    String id,
    String actor,
    String action,
    String targetType,
    String targetId,
    String outcome,
    String detail,
    String createdAt) {
  public Map<String, Object> apiFields() {
    var fields = new LinkedCaseInsensitiveMap<Object>();
    fields.put("ID", id);
    fields.put("ACTOR", actor);
    fields.put("ACTION", action);
    fields.put("TARGET_TYPE", targetType);
    fields.put("TARGET_ID", targetId);
    fields.put("OUTCOME", outcome);
    fields.put("DETAIL", detail);
    fields.put("CREATED_AT", createdAt);
    return fields;
  }
}