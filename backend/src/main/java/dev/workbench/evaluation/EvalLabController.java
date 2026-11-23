package dev.workbench.evaluation;

import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 评测实验室 API：批次、Benchmark Hub 与自定义评测集。 */
@RestController
@RequestMapping("/api/eval-lab")
public class EvalLabController {
  private final EvalLabService lab;
  private final EvalBenchmarkHubService hub;
  private final EvalCustomDatasetService datasets;

  public EvalLabController(
      EvalLabService lab, EvalBenchmarkHubService hub, EvalCustomDatasetService datasets) {
    this.lab = lab;
    this.hub = hub;
    this.datasets = datasets;
  }

  public record Created(String id) {}

  @GetMapping("/meta")
  public Map<String, Object> meta() {
    return lab.meta();
  }

  @PostMapping("/runs")
  public Created start(
      @RequestBody EvalLabService.StartRequest request,
      @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage) {
    return new Created(lab.start(request, acceptLanguage));
  }

  @GetMapping("/runs")
  public List<EvalLabService.RunView> list() {
    return lab.list();
  }

  @GetMapping("/runs/{id}")
  public EvalLabService.Detail detail(@PathVariable String id) {
    return lab.detail(id);
  }

  @PostMapping("/runs/{id}/cancel")
  public Map<String, Object> cancel(@PathVariable String id) {
    lab.cancel(id);
    return Map.of("cancelled", true);
  }

  @GetMapping("/hub")
  public List<EvalBenchmarkHubService.PackView> hub() {
    return hub.list();
  }

  /** 在线下载；URL 白名单、大小与 SHA-256 均在服务层强制校验。 */
  @PostMapping("/hub/{packId}/download")
  public EvalBenchmarkHubService.ImportResult download(@PathVariable String packId) {
    return hub.download(packId);
  }

  /** 网络受限时的离线 JSONL 导入，与在线下载走同一适配器。 */
  @PostMapping("/hub/{packId}/import")
  public EvalBenchmarkHubService.ImportResult importPack(
      @PathVariable String packId, @RequestBody EvalBenchmarkHubService.ImportRequest request) {
    return hub.importJsonl(packId, request == null ? null : request.jsonl());
  }

  @GetMapping("/datasets")
  public List<EvalCustomDatasetService.DatasetView> datasetList() {
    return datasets.list();
  }

  @PostMapping("/datasets")
  public EvalCustomDatasetService.DatasetView createDataset(
      @RequestBody EvalCustomDatasetService.DatasetInput input) {
    return datasets.create(input);
  }

  @PutMapping("/datasets/{id}")
  public EvalCustomDatasetService.DatasetView updateDataset(
      @PathVariable String id, @RequestBody EvalCustomDatasetService.DatasetInput input) {
    return datasets.update(id, input);
  }

  @DeleteMapping("/datasets/{id}")
  public Map<String, Object> deleteDataset(@PathVariable String id) {
    datasets.delete(id);
    return Map.of("deleted", true);
  }

  @GetMapping("/datasets/{id}/samples")
  public List<EvalCustomDatasetService.SampleView> datasetSamples(@PathVariable String id) {
    return datasets.samples(id);
  }

  @PutMapping("/datasets/{id}/samples/{sampleKey}")
  public EvalCustomDatasetService.SampleView saveSample(
      @PathVariable String id,
      @PathVariable String sampleKey,
      @RequestBody EvalCustomDatasetService.SampleInput input) {
    if (input.sampleKey() != null && !input.sampleKey().equals(sampleKey))
      throw new IllegalArgumentException("路径样本标识与请求体不一致。");
    return datasets.upsertSample(id, sampleKey, input);
  }

  @DeleteMapping("/datasets/{id}/samples/{sampleKey}")
  public Map<String, Object> deleteSample(
      @PathVariable String id, @PathVariable String sampleKey) {
    datasets.deleteSample(id, sampleKey);
    return Map.of("deleted", true);
  }

  @PostMapping("/datasets/{id}/import")
  public EvalCustomDatasetService.ImportResult importDataset(
      @PathVariable String id, @RequestBody EvalBenchmarkHubService.ImportRequest request) {
    return datasets.importJsonl(id, request == null ? null : request.jsonl());
  }

  @GetMapping("/datasets/{id}/export")
  public Map<String, Object> exportDataset(@PathVariable String id) {
    return Map.of("jsonl", datasets.exportJsonl(id));
  }
}
