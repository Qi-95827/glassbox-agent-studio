package dev.workbench.evaluation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.workbench.audit.AuditService;
import dev.workbench.auth.AuthService;
import dev.workbench.domain.RunStatus;
import dev.workbench.domain.TaskDefinition;
import dev.workbench.orchestration.CreateTaskRequest;
import dev.workbench.orchestration.RunService;
import dev.workbench.ownership.OwnershipService;
import dev.workbench.persistence.entity.EvaluationDatasetSampleEntity;
import dev.workbench.persistence.entity.EvaluationDatasetVersionEntity;
import dev.workbench.persistence.entity.ManagedEvaluationBatchEntity;
import dev.workbench.persistence.entity.ManagedEvaluationCaseEntity;
import dev.workbench.persistence.repository.EvaluationDatasetSampleRepository;
import dev.workbench.persistence.repository.EvaluationDatasetVersionRepository;
import dev.workbench.persistence.repository.ManagedEvaluationBatchRepository;
import dev.workbench.persistence.repository.ManagedEvaluationCaseRepository;
import dev.workbench.report.SensitiveTextRedactor;
import dev.workbench.store.RunCompletionWaiter;
import dev.workbench.store.RunStateStore;
import dev.workbench.store.TaskRecordService;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 可管理的评测数据集。
 *
 * <p>草稿样本可编辑；发布版本保存不可变快照与摘要。批次绑定实际版本、配置和预算，结果保留关联运行，失败案例可直接回到原执行流程图。
 */
@Service
public class ManagedEvaluationDatasetService {
  private final EvaluationDatasetSampleRepository samples;
  private final EvaluationDatasetVersionRepository versions;
  private final ManagedEvaluationBatchRepository batches;
  private final ManagedEvaluationCaseRepository cases;
  private final RunService runs;
  private final RunStateStore records;
  private final TaskRecordService tasks;
  private final ObjectMapper json;
  private final AuditService audit;
  private final AuthService auth;
  private final OwnershipService ownership;
  private final ExecutorService executor;
  private final RunCompletionWaiter completionWaiter;

  public ManagedEvaluationDatasetService(
      EvaluationDatasetSampleRepository samples,
      EvaluationDatasetVersionRepository versions,
      ManagedEvaluationBatchRepository batches,
      ManagedEvaluationCaseRepository cases,
      RunService runs,
      RunStateStore records,
      TaskRecordService tasks,
      ObjectMapper json,
      AuditService audit,
      AuthService auth,
      OwnershipService ownership,
      @org.springframework.beans.factory.annotation.Qualifier("evaluationExecutor")
          ExecutorService executor,
      RunCompletionWaiter completionWaiter) {
    this.samples = samples;
    this.versions = versions;
    this.batches = batches;
    this.cases = cases;
    this.runs = runs;
    this.records = records;
    this.tasks = tasks;
    this.json = json;
    this.audit = audit;
    this.auth = auth;
    this.ownership = ownership;
    this.executor = executor;
    this.completionWaiter = completionWaiter;
  }

  /**
   * 多用户模式下数据集是平台级共享资产：普通用户改写它即可向他人评测注入样本。
   *
   * <p>尚未初始化账号的单机本地模式没有用户体系，保持既有本地能力。
   */
  private void requireAdmin() {
    if (!auth.initialized()) return;
    auth.requireAdmin();
  }

  /** 发布者一律取服务端当前登录账号；请求体自报的发布者不可信。 */
  private String currentActor() {
    return auth.current()
        .map(dev.workbench.persistence.entity.UserEntity::getUsername)
        .orElse(AuditService.LOCAL_ACTOR);
  }

  @PreDestroy
  void shutdown() {
    executor.shutdownNow();
  }

  public List<Sample> draft(String datasetKey) {
    key(datasetKey);
    return samples.findByDatasetKeyOrderByCodeAsc(datasetKey).stream()
        .map(ManagedEvaluationDatasetService::toSample)
        .toList();
  }

  /** 新增或更新草稿；code 不变时保留原记录并递增版本。 */
  @Transactional
  public Sample upsert(String datasetKey, SampleInput input) {
    requireAdmin();
    key(datasetKey);
    validate(input);
    String time = Instant.now().toString();
    var existing = samples.findByDatasetKeyAndCode(datasetKey, input.code()).orElse(null);
    if (existing == null) {
      var saved =
          samples.saveAndFlush(
              new EvaluationDatasetSampleEntity(
                  dev.workbench.domain.WorkbenchIds.newId(),
                  datasetKey,
                  input.code(),
                  input.title().strip(),
                  redact(input.goal().strip()),
                  input.scenario(),
                  input.expectedStatus(),
                  input.maxRounds(),
                  input.maxTools(),
                  input.sourceKind() == null ? "MANUAL" : input.sourceKind(),
                  input.sourceRef(),
                  true,
                  1,
                  time,
                  time));
      return toSample(saved);
    }
    existing.update(
        input.title().strip(),
        redact(input.goal().strip()),
        input.scenario(),
        input.expectedStatus(),
        input.maxRounds(),
        input.maxTools(),
        existing.getVersion() + 1,
        time);
    return toSample(samples.save(existing));
  }

  @Transactional
  public void delete(String datasetKey, String code) {
    requireAdmin();
    key(datasetKey);
    var existing =
        samples
            .findByDatasetKeyAndCode(datasetKey, code)
            .orElseThrow(() -> new IllegalArgumentException("样本不存在。"));
    samples.delete(existing);
  }

  /** 发布不可变版本；空数据集和定义摘要重复都拒绝。 */
  @Transactional
  public Published publish(String datasetKey, String publishedBy) {
    requireAdmin();
    // 请求体里的 publishedBy 只是历史兼容字段：发布者以服务端会话为准，避免伪造发布人。
    String actor = currentActor();
    key(datasetKey);
    var rows = samples.findByDatasetKeyOrderByCodeAsc(datasetKey);
    if (rows.isEmpty()) throw new IllegalStateException("数据集没有可发布样本。");
    List<SampleInput> snapshot =
        rows.stream()
            .map(
                row ->
                    new SampleInput(
                        row.getCode(),
                        row.getTitle(),
                        row.getGoal(),
                        row.getScenario(),
                        row.getExpectedStatus(),
                        row.getMaxRounds(),
                        row.getMaxTools(),
                        row.getSourceKind(),
                        row.getSourceRef()))
            .toList();
    String definition;
    try {
      definition =
          json.writeValueAsString(
              new LinkedHashMap<String, Object>() {
                {
                  put("dataset", datasetKey);
                  put("assertionVersion", 1);
                  put("samples", snapshot);
                }
              });
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("数据集快照无法序列化。", e);
    }
    String sha = sha(definition);
    int version =
        versions
            .findFirstByDatasetKeyOrderByVersionDesc(datasetKey)
            .map(row -> row.getVersion() + 1)
            .orElse(1);
    var saved =
        versions.saveAndFlush(
            new EvaluationDatasetVersionEntity(
                dev.workbench.domain.WorkbenchIds.newId(),
                datasetKey,
                version,
                definition,
                sha,
                snapshot.size(),
                actor,
                Instant.now().toString()));
    audit.record(
        "EVALUATION_DATASET_PUBLISH",
        "EVALUATION_DATASET",
        datasetKey + "@" + version,
        "PUBLISHED",
        "样本 " + snapshot.size() + "；摘要 " + sha);
    return new Published(
        saved.getId(), datasetKey, version, sha, snapshot.size(), saved.getCreatedAt());
  }

  public List<Published> versions(String datasetKey) {
    key(datasetKey);
    return versions.findByDatasetKeyOrderByVersionDesc(datasetKey).stream()
        .map(
            row ->
                new Published(
                    row.getId(),
                    row.getDatasetKey(),
                    row.getVersion(),
                    row.getDefinitionSha256(),
                    row.getSampleCount(),
                    row.getCreatedAt()))
        .toList();
  }

  /** 从失败运行录入草稿：必须显式确认脱敏，且来源运行不能是成功终态。 */
  @Transactional
  public Sample fromFailure(
      String datasetKey, String runId, boolean confirmedRedaction, String requestedCode) {
    requireAdmin();
    key(datasetKey);
    if (!confirmedRedaction) throw new IllegalArgumentException("必须确认已脱敏并同意录入数据集。");
    if (runId == null || runId.isBlank()) throw new IllegalArgumentException("缺少来源运行标识。");
    // 运行标识不是授权凭证：先校验归属，避免用他人失败运行的目标与证据填充数据集。
    ownership.requireRun(runId);
    var run = records.runSnapshot(runId);
    if (run.status() == RunStatus.COMPLETED) throw new IllegalArgumentException("成功运行不能作为失败案例录入。");
    if (run.status().canCancel()) throw new IllegalStateException("运行尚未结算，不能录入。");
    TaskDefinition task = tasks.taskDefinition(run.taskId().toString());
    String code =
        requestedCode == null || requestedCode.isBlank()
            ? "FROM_" + runId.substring(0, 8).toUpperCase(Locale.ROOT)
            : requestedCode;
    if (!code.matches("[A-Z0-9_-]{1,40}"))
      throw new IllegalArgumentException("样本编码须为 1 至 40 位大写字母、数字、下划线或连字符。");
    String title = "失败案例 " + runId.substring(0, 8);
    String goal = redact(task.goal());
    var existing = samples.findByDatasetKeyAndCode(datasetKey, code).orElse(null);
    String time = Instant.now().toString();
    if (existing == null) {
      var saved =
          samples.saveAndFlush(
              new EvaluationDatasetSampleEntity(
                  dev.workbench.domain.WorkbenchIds.newId(),
                  datasetKey,
                  code,
                  title,
                  goal,
                  task.scenario().code(),
                  run.status().name(),
                  run.maxRounds(),
                  run.maxTools(),
                  "FAILED_RUN",
                  runId,
                  true,
                  1,
                  time,
                  time));
      return toSample(saved);
    }
    existing.update(
        title,
        goal,
        task.scenario().code(),
        run.status().name(),
        run.maxRounds(),
        run.maxTools(),
        existing.getVersion() + 1,
        time);
    return toSample(samples.save(existing));
  }

  /** 启动批次：绑定发布版本、配置和预算；每个样本都保留关联运行。 */
  public Batch start(String datasetKey, Integer version, BatchConfig config) {
    requireAdmin();
    key(datasetKey);
    var resolved =
        (version == null
                ? versions.findFirstByDatasetKeyOrderByVersionDesc(datasetKey)
                : versions.findByDatasetKeyOrderByVersionDesc(datasetKey).stream()
                    .filter(row -> row.getVersion() == version)
                    .findFirst())
            .orElseThrow(() -> new IllegalArgumentException("数据集版本不存在，请先发布。"));
    List<SampleInput> snapshot = read(resolved.getDefinitionJson());
    if (snapshot.isEmpty()) throw new IllegalStateException("数据集版本没有样本。");
    String configJson;
    try {
      configJson = json.writeValueAsString(config == null ? BatchConfig.defaults() : config);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("评测配置无法保存。", e);
    }
    var batch =
        batches.saveAndFlush(
            new ManagedEvaluationBatchEntity(
                dev.workbench.domain.WorkbenchIds.newId(),
                datasetKey,
                resolved.getId(),
                resolved.getDefinitionSha256(),
                configJson,
                sha(configJson),
                "RUNNING",
                Instant.now().toString()));
    for (var sample : snapshot) {
      cases.save(
          new ManagedEvaluationCaseEntity(
              dev.workbench.domain.WorkbenchIds.newId(),
              batch.getId(),
              sample.code(),
              sample.title(),
              sample.expectedStatus(),
              "PENDING",
              Instant.now().toString()));
    }
    try {
      executor.submit(
          () -> execute(batch.getId(), snapshot, config == null ? BatchConfig.defaults() : config));
    } catch (RuntimeException e) {
      batch.finish("FAILED", Instant.now().toString());
      batches.save(batch);
      audit.record(
          "EVALUATION_DATASET_BATCH", "EVALUATION_BATCH", batch.getId(), "FAILED", "执行队列已满");
      throw new IllegalStateException("评测执行队列已满，批次已标记失败，请稍后重试。", e);
    }
    audit.record(
        "EVALUATION_DATASET_BATCH",
        "EVALUATION_BATCH",
        batch.getId(),
        "RUNNING",
        datasetKey + " v" + resolved.getVersion());
    return detail(batch.getId());
  }

  private void execute(String batchId, List<SampleInput> snapshot, BatchConfig config) {
    String finalStatus = "COMPLETED";
    try {
      for (var sample : snapshot) {
        var row =
            cases.findByBatchIdOrderBySampleCodeAsc(batchId).stream()
                .filter(item -> item.getSampleCode().equals(sample.code()))
                .findFirst()
                .orElseThrow();
        String runId;
        try {
          runId =
              runs.create(
                  CreateTaskRequest.builder()
                      .goal(sample.goal())
                      .scenario(sample.scenario())
                      .limits(
                          config.maxRounds() == null
                              ? sample.maxRounds()
                              : Math.min(sample.maxRounds(), config.maxRounds()),
                          config.maxTools() == null
                              ? sample.maxTools()
                              : Math.min(sample.maxTools(), config.maxTools()),
                          config.maxSeconds() == null ? 30 : config.maxSeconds())
                      .modelMode("mock")
                      .build());
        } catch (RuntimeException e) {
          row.finish("FAILED", null, "无法创建运行：" + safe(e.getMessage()), Instant.now().toString());
          cases.save(row);
          continue;
        }
        RunStatus status = waitFor(runId);
        boolean passed = status.name().equals(sample.expectedStatus());
        row.finish(
            passed ? "PASSED" : "FAILED",
            runId,
            "预期 " + sample.expectedStatus() + "；实际 " + status.name(),
            Instant.now().toString());
        cases.save(row);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      finalStatus = "INTERRUPTED";
    } catch (RuntimeException e) {
      finalStatus = "FAILED";
    } finally {
      var batch = batches.findById(batchId).orElse(null);
      if (batch != null) {
        batch.finish(finalStatus, Instant.now().toString());
        batches.save(batch);
      }
    }
  }

  private RunStatus waitFor(String runId) throws InterruptedException {
    long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MINUTES.toNanos(5);
    var future = completionWaiter.register(runId);
    try {
      RunStatus status = records.runSnapshot(runId).status();
      if (!status.canCancel()) return status;
      long remaining = deadline - System.nanoTime();
      if (remaining > 0) {
        completionWaiter.await(
            future, java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(remaining));
        status = records.runSnapshot(runId).status();
        if (!status.canCancel()) return status;
      }
      try {
        runs.cancel(runId);
      } catch (IllegalStateException ignored) {
        /* 可能已经结束。 */
      }
      throw new IllegalStateException("评测等待运行超过五分钟。");
    } finally {
      completionWaiter.remove(runId, future);
    }
  }

  public List<BatchSummary> list() {
    return batches.findAllByOrderByCreatedAtDesc().stream()
        .map(
            row ->
                new BatchSummary(
                    row.getId(),
                    row.getDatasetKey(),
                    row.getDatasetVersionId(),
                    row.getDefinitionSha256(),
                    row.getConfigJson(),
                    row.getStatus(),
                    row.getCreatedAt(),
                    row.getFinishedAt()))
        .toList();
  }

  public Batch detail(String batchId) {
    var batch =
        batches.findById(batchId).orElseThrow(() -> new IllegalArgumentException("评测批次不存在。"));
    var items =
        cases.findByBatchIdOrderBySampleCodeAsc(batchId).stream()
            .map(
                row ->
                    new CaseResult(
                        row.getId(),
                        row.getSampleCode(),
                        row.getTitle(),
                        row.getExpectedStatus(),
                        row.getStatus(),
                        row.getRunId(),
                        row.getExplanation(),
                        row.getUpdatedAt()))
            .toList();
    return new Batch(
        batch.getId(),
        batch.getDatasetKey(),
        batch.getDatasetVersionId(),
        batch.getDefinitionSha256(),
        batch.getConfigJson(),
        batch.getStatus(),
        batch.getCreatedAt(),
        batch.getFinishedAt(),
        items);
  }

  private List<SampleInput> read(String definition) {
    try {
      var node = json.readTree(definition);
      var list = node.get("samples");
      return json.convertValue(
          list, json.getTypeFactory().constructCollectionType(List.class, SampleInput.class));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("已发布的数据集定义无效。", e);
    }
  }

  private static void validate(SampleInput input) {
    if (input == null) throw new IllegalArgumentException("样本不能为空。");
    if (input.code() == null || !input.code().matches("[A-Z0-9_-]{1,40}"))
      throw new IllegalArgumentException("样本编码须为 1 至 40 位大写字母、数字、下划线或连字符。");
    if (input.title() == null || input.title().isBlank() || input.title().length() > 120)
      throw new IllegalArgumentException("样本标题须为 1 至 120 字。");
    if (input.goal() == null || input.goal().isBlank() || input.goal().length() > 500)
      throw new IllegalArgumentException("样本目标须为 1 至 500 字。");
    if (!List.of("normal", "fail", "repeat").contains(input.scenario()))
      throw new IllegalArgumentException("样本场景无效。");
    if (!List.of("COMPLETED", "FAILED", "LIMITED", "BLOCKED", "CANCELLED")
        .contains(input.expectedStatus())) throw new IllegalArgumentException("预期状态无效。");
    if (input.maxRounds() < 1
        || input.maxRounds() > 20
        || input.maxTools() < 0
        || input.maxTools() > 20) throw new IllegalArgumentException("样本预算超出允许范围。");
  }

  private static String redact(String text) {
    return SensitiveTextRedactor.redact(text);
  }

  private static String safe(String text) {
    if (text == null) return "未知原因。";
    return text.length() <= 900 ? text : text.substring(0, 899) + "…";
  }

  private static void key(String datasetKey) {
    if (datasetKey == null || !datasetKey.matches("[A-Z0-9_-]{1,40}"))
      throw new IllegalArgumentException("数据集编码须为 1 至 40 位大写字母、数字、下划线或连字符。");
  }

  private static Sample toSample(EvaluationDatasetSampleEntity row) {
    return new Sample(
        row.getId(),
        row.getDatasetKey(),
        row.getCode(),
        row.getTitle(),
        row.getGoal(),
        row.getScenario(),
        row.getExpectedStatus(),
        row.getMaxRounds(),
        row.getMaxTools(),
        row.getSourceKind(),
        row.getSourceRef(),
        row.isRedacted(),
        row.getVersion(),
        row.getUpdatedAt());
  }

  private static String sha(String text) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  public record SampleInput(
      String code,
      String title,
      String goal,
      String scenario,
      String expectedStatus,
      int maxRounds,
      int maxTools,
      String sourceKind,
      String sourceRef) {}

  public record Sample(
      String id,
      String datasetKey,
      String code,
      String title,
      String goal,
      String scenario,
      String expectedStatus,
      int maxRounds,
      int maxTools,
      String sourceKind,
      String sourceRef,
      boolean redacted,
      int version,
      String updatedAt) {}

  public record Published(
      String id,
      String datasetKey,
      int version,
      String definitionSha256,
      int sampleCount,
      String createdAt) {}

  public record BatchConfig(Integer maxRounds, Integer maxTools, Integer maxSeconds) {
    public static BatchConfig defaults() {
      return new BatchConfig(null, null, null);
    }
  }

  public record CaseResult(
      String id,
      String sampleCode,
      String title,
      String expectedStatus,
      String status,
      String runId,
      String explanation,
      String updatedAt) {}

  public record Batch(
      String id,
      String datasetKey,
      String datasetVersionId,
      String definitionSha256,
      String configJson,
      String status,
      String createdAt,
      String finishedAt,
      List<CaseResult> cases) {}

  public record BatchSummary(
      String id,
      String datasetKey,
      String datasetVersionId,
      String definitionSha256,
      String configJson,
      String status,
      String createdAt,
      String finishedAt) {}
}
