package dev.workbench.observability;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** 查询指定运行的 Agent 指标快照。 */
@RestController
public class RunMetricsController {
  private final RunMetricsService metrics;

  public RunMetricsController(RunMetricsService metrics) {
    this.metrics = metrics;
  }

  @GetMapping("/api/runs/{id}/agent-metrics")
  public List<AgentMetrics> metrics(@PathVariable String id) {
    return metrics.agents(id);
  }
}
