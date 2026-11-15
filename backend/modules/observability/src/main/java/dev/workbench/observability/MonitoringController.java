package dev.workbench.observability;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 本地版低敏系统运行概况。 */
@RestController
public class MonitoringController {
  private final MonitoringService monitoring;

  public MonitoringController(MonitoringService monitoring) {
    this.monitoring = monitoring;
  }

  @GetMapping("/api/monitoring")
  public MonitoringOverview overview() {
    return monitoring.overview();
  }
}
