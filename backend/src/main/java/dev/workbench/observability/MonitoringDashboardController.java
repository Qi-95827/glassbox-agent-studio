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

  /**
   * 全局或单任务监控看板。
   *
   * <p>不传 {@code taskId}/{@code runId} 时保持原全局行为；传入时按归属收敛到单任务或单次运行，越权一律 404，
   * 绝不回退成全局数据。
   */
  @GetMapping("/api/monitoring/dashboard")
  public MonitoringDashboard dashboard(
      @RequestParam(defaultValue = "7d") String range,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String model,
      @RequestParam(required = false) String taskId,
      @RequestParam(required = false) String runId) {
    return dashboard.dashboard(range, status, model, taskId, runId);
  }

  @GetMapping("/api/monitoring/runs/{runId}/diagnostics")
  public RunDiagnostics diagnostics(
      @PathVariable String runId, java.util.Locale locale) {
    ownership.requireRun(runId);
    return diagnostics.diagnose(runId, locale);
  }
}
