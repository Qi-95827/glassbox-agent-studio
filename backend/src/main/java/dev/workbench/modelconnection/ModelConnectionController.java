package dev.workbench.modelconnection;

import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 用户自带模型连接入口；只经由服务层访问数据，不接受请求体中的用户标识。 */
@RestController
@RequestMapping("/api/model-connections")
public class ModelConnectionController {
  private final ModelConnectionService connections;
  private final ModelUsageEstimateService estimates;

  public ModelConnectionController(
      ModelConnectionService connections, ModelUsageEstimateService estimates) {
    this.connections = connections;
    this.estimates = estimates;
  }

  @GetMapping("/presets")
  List<ModelConnectionService.PresetView> presets() {
    return connections.presets();
  }

  @GetMapping
  ModelConnectionService.ListView list() {
    return connections.list();
  }

  @PostMapping
  ModelConnectionService.ConnectionView create(
      @RequestBody ModelConnectionService.SaveRequest request) {
    return connections.create(request);
  }

  @PutMapping("/{id}")
  ModelConnectionService.ConnectionView update(
      @PathVariable String id, @RequestBody ModelConnectionService.SaveRequest request) {
    return connections.update(id, request);
  }

  @DeleteMapping("/{id}")
  void delete(@PathVariable String id) {
    connections.delete(id);
  }

  @PostMapping("/{id}/set-default")
  ModelConnectionService.ConnectionView setDefault(@PathVariable String id) {
    return connections.setDefault(id);
  }

  @PostMapping("/{id}/test")
  ModelConnectionService.TestResult test(@PathVariable String id) {
    return connections.test(id);
  }

  @PostMapping("/test-draft")
  ModelConnectionService.TestResult testDraft(
      @RequestBody ModelConnectionService.TestDraftRequest request) {
    return connections.testDraft(request);
  }

  /** 自带 Key 运行的参考费用估算；未填单价时仍返回完整 Token 统计。 */
  @GetMapping("/runs/{runId}/estimate")
  ModelUsageEstimateService.EstimateView estimateRun(@PathVariable String runId) {
    return estimates.estimate(runId);
  }
}
