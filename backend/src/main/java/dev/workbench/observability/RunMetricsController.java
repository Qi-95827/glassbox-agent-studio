package dev.workbench.observability;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** 查询指定运行的 Agent 指标快照；读取前核对任务归属。 */
@RestController
public class RunMetricsController {
  private final RunMetricsService metrics;
  private final dev.workbench.ownership.OwnershipService ownership;

  public RunMetricsController(
      RunMetricsService metrics, dev.workbench.ownership.OwnershipService ownership) {
    this.metrics = metrics;
    this.ownership = ownership;
  }

  @GetMapping("/api/runs/{id}/agent-metrics")
  public List<AgentMetrics> metrics(@PathVariable String id) {
    ownership.requireRun(id);
    return metrics.agents(id);
  }
}
