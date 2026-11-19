package dev.workbench.observability;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 本地版低敏系统运行概况。 */
@RestController
public class MonitoringController {
  private final MonitoringService monitoring;
  private final AlertService alerts;
  private final SelfCheckService selfCheck;

  public MonitoringController(
      MonitoringService monitoring, AlertService alerts, SelfCheckService selfCheck) {
    this.monitoring = monitoring;
    this.alerts = alerts;
    this.selfCheck = selfCheck;
  }

  @GetMapping("/api/monitoring")
  public MonitoringOverview overview() {
    return monitoring.overview();
  }

  @GetMapping("/api/monitoring/alerts")
  public Object alerts() {
    return alerts.list();
  }

  @GetMapping("/api/system/self-check")
  public Object selfCheck() {
    return selfCheck.check();
  }
}
