package dev.workbench.app;

import dev.workbench.evaluation.ManagedEvaluationDatasetService;
import java.util.List;
import org.springframework.web.bind.annotation.*;

/** 可管理评测数据集、不可变版本和批次结果入口。 */
@RestController
@RequestMapping("/api/evaluation-datasets")
public class EvaluationDatasetController {
  private final ManagedEvaluationDatasetService datasets;

  public EvaluationDatasetController(ManagedEvaluationDatasetService datasets) {
    this.datasets = datasets;
  }

  public record FromRun(String runId, Boolean confirmedRedaction, String code) {}

  public record Publish(String publishedBy) {}

  @GetMapping("/{key}")
  List<ManagedEvaluationDatasetService.Sample> draft(@PathVariable String key) {
    return datasets.draft(key);
  }

  @PutMapping("/{key}/samples/{code}")
  ManagedEvaluationDatasetService.Sample upsert(
      @PathVariable String key,
      @PathVariable String code,
      @RequestBody ManagedEvaluationDatasetService.SampleInput sample) {
    if (sample.code() != null && !sample.code().equals(code))
      throw new IllegalArgumentException("路径编码与请求编码不一致。");
    return datasets.upsert(
        key,
        new ManagedEvaluationDatasetService.SampleInput(
            code,
            sample.title(),
            sample.goal(),
            sample.scenario(),
            sample.expectedStatus(),
            sample.maxRounds(),
            sample.maxTools(),
            sample.sourceKind(),
            sample.sourceRef()));
  }

  @DeleteMapping("/{key}/samples/{code}")
  Object delete(@PathVariable String key, @PathVariable String code) {
    datasets.delete(key, code);
    return java.util.Map.of("deleted", true);
  }

  @PostMapping("/{key}/publish")
  ManagedEvaluationDatasetService.Published publish(
      @PathVariable String key, @RequestBody(required = false) Publish request) {
    return datasets.publish(key, request == null ? null : request.publishedBy());
  }

  @GetMapping("/{key}/versions")
  List<ManagedEvaluationDatasetService.Published> versions(@PathVariable String key) {
    return datasets.versions(key);
  }

  @PostMapping("/{key}/from-run")
  ManagedEvaluationDatasetService.Sample fromRun(
      @PathVariable String key, @RequestBody FromRun request) {
    return datasets.fromFailure(
        key, request.runId(), Boolean.TRUE.equals(request.confirmedRedaction()), request.code());
  }

  @PostMapping("/{key}/batches")
  ManagedEvaluationDatasetService.Batch start(
      @PathVariable String key,
      @RequestParam(required = false) Integer version,
      @RequestBody(required = false) ManagedEvaluationDatasetService.BatchConfig config) {
    return datasets.start(key, version, config);
  }

  @GetMapping("/batches")
  List<ManagedEvaluationDatasetService.BatchSummary> batches() {
    return datasets.list();
  }

  @GetMapping("/batches/{batchId}")
  ManagedEvaluationDatasetService.Batch batch(@PathVariable String batchId) {
    return datasets.detail(batchId);
  }
}
