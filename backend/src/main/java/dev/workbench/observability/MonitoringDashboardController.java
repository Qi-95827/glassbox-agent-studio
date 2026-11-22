package dev.workbench.observability;

import dev.workbench.ownership.OwnershipService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 细粒度运行监控看板与单次运行故障诊断入口。 */
@RestController
public class MonitoringDashboardController {
  private final MonitoringDashboardService dashboard;
  private final RunDiagnosticsService diagnostics;
  private final OwnershipService ownership;

  public MonitoringDashboardController(
      MonitoringDashboardService dashboard,
      RunDiagnosticsService diagnostics,
      OwnershipService ownership) {
    this.dashboard = dashboard;
    this.diagnostics = diagnostics;
    this.ownership = ownership;
  }

  @GetMapping("/api/monitoring/dashboard")
  public MonitoringDashboard dashboard(
      @RequestParam(defaultValue = "7d") String range,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String model) {
    return dashboard.dashboard(range, status, model);
  }

  @GetMapping("/api/monitoring/runs/{runId}/diagnostics")
  public RunDiagnostics diagnostics(@PathVariable String runId) {
    ownership.requireRun(runId);
    return diagnostics.diagnose(runId);
  }
}
