package dev.workbench.persistence.repository;

import java.util.Map;
import org.springframework.util.LinkedCaseInsensitiveMap;

/** 运行状态计数投影。 */
public record RunCounts(Long total, Long running, Long completed) {
  public Map<String, Object> apiFields() {
    var fields = new LinkedCaseInsensitiveMap<Object>();
    fields.put("TOTAL", total);
    fields.put("RUNNING", running);
    fields.put("COMPLETED", completed);
    return fields;
  }
}