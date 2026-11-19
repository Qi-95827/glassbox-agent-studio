package dev.workbench.audit;

import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 只读审计入口；写操作由各受控服务内部记录。 */
@RestController
@RequestMapping("/api/audit")
public class AuditController {
  private final AuditService audit;

  public AuditController(AuditService audit) {
    this.audit = audit;
  }

  @GetMapping
  public List<Map<String, Object>> list(@RequestParam(defaultValue = "50") int limit) {
    return audit.list(limit);
  }
}
