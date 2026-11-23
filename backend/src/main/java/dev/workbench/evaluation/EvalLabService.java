package dev.workbench.evaluation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.workbench.audit.AuditService;
import dev.workbench.domain.WorkbenchIds;
import dev.workbench.domain.WorkbenchTime;
import dev.workbench.evaluation.dataset.BuiltInMiniPackLoader;
import dev.workbench.evaluation.grader.CodeAssertionGrader;
import dev.workbench.evaluation.grader.LlmJudgeGrader;
import dev.workbench.framework.evaluation.EvalAnswerModel;
import dev.workbench.framework.evaluation.EvalCaseExecution;
import dev.workbench.framework.evaluation.EvalCategory;
import dev.workbench.framework.evaluation.EvalCategoryRunner;
import dev.workbench.framework.evaluation.EvalExperimentConfig;
import dev.workbench.framework.evaluation.EvalSample;
import dev.workbench.modelconnection.ModelConnectionService;
import dev.workbench.ownership.OwnershipService;
import dev.workbench.persistence.entity.EvalLabCaseResultEntity;
import dev.workbench.persistence.entity.EvalLabRunEntity;
import dev.workbench.persistence.repository.EvalLabCaseResultRepository;
import dev.workbench.persistence.repository.EvalLabRunRepository;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.stereotype.Service;

/**
 * 评测实验室批次编排。
 *
 * <p>真实执行由六大 {@link EvalCategoryRunner} 完成；这里只负责抽样、消融开关注入、重复 K 次、
 * 费用上限保护、双轨裁判结算与所有权隔离。任何失败都会落为可解释的失败案例，不会生成假分数。
 */
@Service
public class EvalLabService {
  private final EvalLabRunRepository runs;
  private final EvalLabCaseResultRepository cases;
  private final BuiltInMiniPackLoader miniPacks;
  private final Map<EvalCategory, EvalCategoryRunner> runners = new EnumMap<>(EvalCategory.class);
  private final CodeAssertionGrader codeGrader;
  private final LlmJudgeGrader judge;
  private final EvalAnswerModel answerModel;
  private final ModelConnectionService connections;
  private final EvalMetricsAggregator aggregator;
  private final EvalBenchmarkHubService hub;
  private final EvalCustomDatasetService customDatasets;
  private final OwnershipService ownership;
  private final ObjectMapper json;
  private final AuditService audit;
  private final ExecutorService executor;
  private final Set<String> cancellations = ConcurrentHashMap.newKeySet();

  public EvalLabService(
      EvalLabRunRepository runs,
      EvalLabCaseResultRepository cases,
      BuiltInMiniPackLoader miniPacks,
      List<EvalCategoryRunner> runners,
      CodeAssertionGrader codeGrader,
      LlmJudgeGrader judge,
      EvalAnswerModel answerModel,
      ModelConnectionService connections,
      EvalMetricsAggregator aggregator,
      EvalBenchmarkHubService hub,
      EvalCustomDatasetService customDatasets,
      OwnershipService ownership,
      ObjectMapper json,
      AuditService audit,
      @Qualifier("evaluationExecutor") ExecutorService executor) {
    this.runs = runs;
    this.cases = cases;
    this.miniPacks = miniPacks;
    for (var runner : runners) this.runners.put(runner.category(), runner);
    this.codeGrader = codeGrader;
    this.judge = judge;
    this.answerModel = answerModel;
    this.connections = connections;
    this.aggregator = aggregator;
    this.hub = hub;
    this.customDatasets = customDatasets;
    this.ownership = ownership;
    this.json = json;
    this.audit = audit;
    this.executor = executor;
  }

  public record StartRequest(
      String sourceKind,
      String sourceKey,
      List<String> categories,
      String modelMode,
      String modelConnectionId,
      Boolean enableCompaction,
      Boolean enableLongTermMemory,
      Boolean enableReflection,
      Boolean enableMcp,
      Integer repeatK,
      Integer sampleLimit,
      Double maxCostUsd,
      String judgeModelConnectionId) {}

  public record RunView(
      String id,
      String status,
      String sourceKind,
      String sourceKey,
      List<String> categories,
      Map<String, Object> config,
      Map<String, Object> summary,
      boolean judgeEnabled,
      double totalCostUsd,
      int totalTokens,
      long totalDurationMs,
      String errorMessage,
      String locale,
      String createdAt,
      String startedAt,
      String finishedAt) {}

  public record CaseView(
      String id,
      String category,
      String sampleId,
      String titleZh,
      String titleEn,
      String sourcePack,
      int repeatIndex,
      String linkedRunId,
      boolean passed,
      double codeScore,
      Double judgeScore,
      Map<String, Object> metrics,
      List<Map<String, Object>> assertions,
      Map<String, Object> judgeVerdict,
      Map<String, Object> trace,
      String rawOutput,
      long durationMs,
      int inputTokens,
      int outputTokens,
      double costUsd,
      String createdAt) {}

  public record Detail(RunView run, List<CaseView> cases) {}

  public String start(StartRequest request, String requestLocale) {
    if (request == null) throw new IllegalArgumentException("评测请求不能为空。");
    String sourceKind = normalizeSource(request.sourceKind());
    var requestedConfig = buildConfig(request);
    String locale = normalizeLocale(requestLocale);
    String owner = ownership.scope().ownerScopeKey();
    String id = WorkbenchIds.newId();
    String now = WorkbenchTime.now();
    String sourceKey = sourceKind.equals("MINI") ? "mini" : blankToNull(request.sourceKey());
    if (!sourceKind.equals("MINI") && sourceKey == null)
      throw new IllegalArgumentException("该数据源缺少评测集标识。");
    var pack = "HUB".equals(sourceKind) ? hub.requirePack(sourceKey) : null;
    if (pack != null && pack.previewOnly())
      throw new IllegalArgumentException("SWE-bench Lite 官方执行需要独立 Git 与 Docker 环境。");
    var selected = pack == null ? categories(request.categories()) : List.of(pack.evalCategory());
    var config = requestedConfig;
    if (config.usesByok()) connections.requireEnabledOwned(config.modelConnectionId());
    if (config.judgeEnabled()) connections.requireEnabledOwned(config.judgeModelConnectionId());
    // 先固定可执行题单，再把作业交给异步线程，避免重导入影响已经发起的评测。
    var fixedSamples =
        pack == null ? null : hub.samplesForRun(List.of(sourceKey), config.sampleLimit());
    var snapshot = new java.util.LinkedHashMap<>(readMap(write(config)));
    if (fixedSamples != null) {
      if (config.usesByok()
          && config.maxCostUsd() != null
          && !pricedModel(config.modelConnectionId()))
        throw new IllegalArgumentException("使用美元费用上限前，请为被测模型配置美元计价。");
      snapshot.put("datasetName", pack.displayName());
      snapshot.put("sampleIds", fixedSamples.stream().map(EvalSample::id).toList());
      String canonicalSamples =
          json.copy()
              .enable(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
              .valueToTree(fixedSamples)
              .toString();
      snapshot.put("datasetHash", sha(canonicalSamples));
    }
    String configJson = write(snapshot);
    String categoriesJson = write(selected.stream().map(Enum::name).toList());
    var entity =
        new EvalLabRunEntity(
            id,
            owner,
            "QUEUED",
            sourceKind,
            sourceKey,
            categoriesJson,
            configJson,
            sha(configJson),
            config.judgeEnabled(),
            config.judgeModelConnectionId(),
            locale,
            now);
    runs.saveAndFlush(entity);
    var securityContext =
        org.springframework.security.core.context.SecurityContextHolder.getContext();
    var capturedAuth = securityContext == null ? null : securityContext.getAuthentication();
    try {
      executor.submit(
          () -> {
            var asyncContext =
                org.springframework.security.core.context.SecurityContextHolder
                    .createEmptyContext();
            if (capturedAuth != null) asyncContext.setAuthentication(capturedAuth);
            org.springframework.security.core.context.SecurityContextHolder.setContext(
                asyncContext);
            try {
              execute(id, sourceKind, sourceKey, selected, config, locale, owner, fixedSamples);
            } finally {
              org.springframework.security.core.context.SecurityContextHolder.clearContext();
            }
          });
    } catch (RuntimeException e) {
      entity.finish("FAILED", write(Map.of("error", "执行队列已满")), 0, 0, 0, "执行队列已满");
      runs.save(entity);
      throw new IllegalStateException("评测执行队列已满，请稍后重试。", e);
    }
    audit.record(
        "EVAL_LAB_START",
        "EVAL_LAB_RUN",
        id,
        "QUEUED",
        sourceKind + " / " + selected.size() + " 类 / repeatK=" + config.repeatK());
    return id;
  }

  public List<RunView> list() {
    return runs.findAllByOrderByCreatedAtDesc().stream()
        .filter(row -> ownership.canRead(row.getOwnerScopeKey()))
        .map(row -> toView(row))
        .toList();
  }

  /** 前端元数据：类别双语标签、内置微集题量与默认实验开关。 */
  public Map<String, Object> meta() {
    var counts = miniPacks.countsByCategory();
    var categories = new ArrayList<Map<String, Object>>();
    for (var category : EvalCategory.values()) {
      categories.add(
          Map.of(
              "code", category.name(),
              "labelZh", category.labelZh(),
              "labelEn", category.labelEn(),
              "miniCount", counts.getOrDefault(category, 0)));
    }
    return Map.of(
        "categories",
        List.copyOf(categories),
        "sources",
        List.of("MINI", "HUB", "CUSTOM"),
        "defaultSampleLimit",
        4,
        "maxRepeatK",
        EvalExperimentConfig.MAX_REPEAT,
        "maxSampleLimit",
        EvalExperimentConfig.MAX_SAMPLE_LIMIT);
  }

  public Detail detail(String id) {
    var row = requireReadable(id);
    var items =
        cases.findByEvalRunIdOrderByCategoryAscSampleIdAscRepeatIndexAsc(id).stream()
            .map(this::toCaseView)
            .toList();
    return new Detail(toView(row), items);
  }

  public void cancel(String id) {
    var row = requireReadable(id);
    if (!row.getStatus().equals("RUNNING") && !row.getStatus().equals("QUEUED"))
      throw new IllegalStateException("评测已结束，无法取消。");
    cancellations.add(id);
    audit.record("EVAL_LAB_CANCEL", "EVAL_LAB_RUN", id, "CANCELLING", "用户请求取消评测");
  }

  @PreDestroy
  void shutdown() {
    executor.shutdownNow();
  }

  private void execute(
      String id,
      String sourceKind,
      String sourceKey,
      List<EvalCategory> selected,
      EvalExperimentConfig config,
      String locale,
      String owner,
      List<EvalSample> fixedSamples) {
    var row = runs.findById(id).orElse(null);
    if (row == null) return;
    row.start(WorkbenchTime.now());
    runs.save(row);
    var rows = new ArrayList<EvalMetricsAggregator.CaseRow>();
    double modelCost = 0;
    double judgeCost = 0;
    int tokens = 0;
    long duration = 0;
    boolean budgetStopped = false;
    String fatal = null;
    String finalStatus = "COMPLETED";
    try {
      var samples =
          fixedSamples == null
              ? resolveSamples(sourceKind, sourceKey, selected, config.sampleLimit())
              : fixedSamples;
      boolean hubRun = fixedSamples != null;
      outer:
      for (var category : selected) {
        var runner = runners.get(category);
        if (runner == null) throw new IllegalStateException("缺少评测执行器：" + category);
        var categorySamples =
            samples.stream().filter(sample -> sample.category() == category).toList();
        for (var sample : categorySamples) {
          for (int repeat = 1; repeat <= config.repeatK(); repeat++) {
            if (cancellations.contains(id)) {
              finalStatus = "CANCELLED";
              break outer;
            }
            if (config.maxCostUsd() != null && modelCost + judgeCost >= config.maxCostUsd()) {
              budgetStopped = true;
              break outer;
            }
            EvalCaseExecution execution;
            try {
              execution = runner.run(sample, config, owner);
            } catch (RuntimeException e) {
              String reason = hubRun ? "AGENT_EXECUTION_ERROR" : safe(e.getMessage());
              execution =
                  EvalCaseExecution.failed(
                      reason,
                      Map.of(
                          "category",
                          category.name(),
                          "sampleId",
                          sample.id(),
                          "errorCode",
                          reason));
            }
            boolean executionFailed =
                Boolean.TRUE.equals(execution.metrics().get("executionFailed"));
            boolean agentGrade = Boolean.TRUE.equals(execution.evidence().get("agentGrade"));
            var code =
                executionFailed
                    ? new CodeAssertionGrader.Graded(List.of(), 0, false)
                    : agentGrade
                        ? codeGrader.gradeAgent(category, execution, false)
                        : codeGrader.grade(sample.assertions(), execution);
            var reference = execution.trace().get("referenceAnswer");
            var answer = execution.trace().get("modelAnswer");
            boolean judgeInputAvailable =
                !agentGrade
                    || (reference == null
                        ? !execution.rawOutput().isBlank()
                        : answer instanceof String text && !text.isBlank());
            var verdict =
                config.judgeEnabled()
                        && !cancellations.contains(id)
                        && !executionFailed
                        && judgeInputAvailable
                    ? judge.judge(
                        sample,
                        execution.withAssertions(code.assertions(), code.score()),
                        config.judgeModelConnectionId(),
                        locale,
                        reference instanceof String text ? text : null)
                    : LlmJudgeGrader.JudgeResult.disabled();
            boolean judgePassed =
                "BYOK".equals(verdict.mode()) && Boolean.TRUE.equals(verdict.passed());
            var graded =
                agentGrade && !executionFailed && judgePassed
                    ? codeGrader.gradeAgent(category, execution, true)
                    : code;
            boolean passed =
                graded.passed()
                    && (agentGrade
                        || !"BYOK".equals(verdict.mode())
                        || !Boolean.FALSE.equals(verdict.passed()));
            var executionWithScore = execution.withAssertions(graded.assertions(), graded.score());
            var metrics = new LinkedHashMapCopy(executionWithScore.metrics());
            metrics.put("codePassed", code.passed());
            var caseCost = executionWithScore.costUsd();
            modelCost += caseCost;
            judgeCost += verdict.costUsd();
            tokens +=
                executionWithScore.inputTokens()
                    + executionWithScore.outputTokens()
                    + verdict.inputTokens()
                    + verdict.outputTokens();
            duration += executionWithScore.durationMs();
            var now = WorkbenchTime.now();
            var trace = new java.util.LinkedHashMap<String, Object>(executionWithScore.trace());
            trace.putIfAbsent("prompt", sample.prompt());
            if (!hubRun) trace.putIfAbsent("setup", sample.setup());
            trace.putIfAbsent("category", category.name());
            trace.putIfAbsent("sampleId", sample.id());
            cases.save(
                new EvalLabCaseResultEntity(
                    WorkbenchIds.newId(),
                    id,
                    category.name(),
                    sample.id(),
                    sample.titleZh(),
                    sample.titleEn(),
                    sample.sourcePack(),
                    repeat,
                    executionWithScore.linkedRunId(),
                    passed,
                    graded.score(),
                    verdict.score() == null ? null : verdict.score().doubleValue(),
                    write(metrics),
                    write(graded.assertions()),
                    write(verdictMap(verdict)),
                    write(trace),
                    trim(executionWithScore.rawOutput(), 20_000),
                    executionWithScore.durationMs(),
                    executionWithScore.inputTokens(),
                    executionWithScore.outputTokens(),
                    caseCost,
                    now));
            rows.add(
                new EvalMetricsAggregator.CaseRow(
                    category,
                    sample.id(),
                    repeat,
                    passed,
                    graded.score(),
                    executionWithScore.durationMs(),
                    caseCost,
                    metrics));
            row.progress(modelCost, tokens, duration);
            runs.save(row);
            if (hubRun
                && config.usesByok()
                && config.maxCostUsd() != null
                && Boolean.TRUE.equals(metrics.get("modelRequestAttempted"))
                && !Boolean.TRUE.equals(metrics.get("costKnown"))
                && !executionFailed) {
              budgetStopped = true;
              break outer;
            }
          }
        }
      }
      var summary = aggregator.aggregate(rows, config.repeatK(), budgetStopped);
      summary.put("judgeCostUsd", round(judgeCost));
      summary.put("modelCostUsd", round(modelCost));
      summary.put("judgeEnabled", config.judgeEnabled());
      if (hubRun) {
        summary.put("plannedAttempts", samples.size() * config.repeatK());
        summary.put(
            "unscoredAttempts",
            rows.stream()
                .filter(result -> Boolean.TRUE.equals(result.metrics().get("executionFailed")))
                .count());
        summary.put(
            "knownModelCosts",
            rows.stream()
                .filter(result -> Boolean.TRUE.equals(result.metrics().get("costKnown")))
                .count());
      }
      var finished = WorkbenchTime.now();
      row.finish(finalStatus, write(summary), modelCost, tokens, duration, null);
      runs.save(row);
      audit.record(
          "EVAL_LAB_FINISH",
          "EVAL_LAB_RUN",
          id,
          finalStatus,
          rows.size() + " 题次；模型费用 " + round(modelCost) + "；裁判费用 " + round(judgeCost));
    } catch (RuntimeException e) {
      fatal = safe(e.getMessage());
      finalStatus = "FAILED";
      row.finish("FAILED", write(Map.of("error", fatal)), modelCost, tokens, duration, fatal);
      runs.save(row);
      audit.record("EVAL_LAB_FINISH", "EVAL_LAB_RUN", id, "FAILED", fatal);
    } finally {
      cancellations.remove(id);
    }
  }

  private boolean pricedModel(String connectionId) {
    try {
      return answerModel.open(connectionId).pricedInUsd();
    } catch (RuntimeException ignored) {
      return false;
    }
  }

  private List<EvalSample> resolveSamples(
      String sourceKind, String sourceKey, List<EvalCategory> categories, int sampleLimit) {
    List<EvalSample> pool;
    if ("MINI".equals(sourceKind)) {
      pool = miniPacks.loadAll();
    } else {
      pool = customDatasets.samplesForRun(sourceKey);
    }
    var result = new ArrayList<EvalSample>();
    for (var category : categories)
      result.addAll(
          pool.stream()
              .filter(sample -> sample.category() == category)
              .limit(sampleLimit)
              .toList());
    return List.copyOf(result);
  }

  private EvalLabRunEntity requireReadable(String id) {
    if (id == null || id.isBlank()) throw new IllegalArgumentException("缺少评测批次标识。");
    var row = runs.findById(id).orElseThrow(() -> new EmptyResultDataAccessException(1));
    if (!ownership.canRead(row.getOwnerScopeKey())) throw new EmptyResultDataAccessException(1);
    return row;
  }

  private EvalExperimentConfig buildConfig(StartRequest request) {
    return new EvalExperimentConfig(
        request.modelMode(),
        request.modelConnectionId(),
        request.enableCompaction() == null || request.enableCompaction(),
        request.enableLongTermMemory() == null || request.enableLongTermMemory(),
        request.enableReflection() == null || request.enableReflection(),
        Boolean.TRUE.equals(request.enableMcp()),
        request.repeatK() == null ? 1 : request.repeatK(),
        request.sampleLimit() == null
            ? ("HUB".equalsIgnoreCase(request.sourceKind()) ? 5 : 4)
            : request.sampleLimit(),
        request.maxCostUsd(),
        request.judgeModelConnectionId());
  }

  private static List<EvalCategory> categories(List<String> raw) {
    if (raw == null || raw.isEmpty()) return List.of(EvalCategory.values());
    var result = new ArrayList<EvalCategory>();
    for (String item : raw) {
      var category = EvalCategory.parse(item);
      if (!result.contains(category)) result.add(category);
    }
    return List.copyOf(result);
  }

  private static String normalizeSource(String raw) {
    String value = raw == null || raw.isBlank() ? "MINI" : raw.strip().toUpperCase(Locale.ROOT);
    if (!List.of("MINI", "HUB", "CUSTOM").contains(value))
      throw new IllegalArgumentException("未知评测数据源：" + raw);
    return value;
  }

  private static String normalizeLocale(String locale) {
    return locale != null && locale.strip().toLowerCase(Locale.ROOT).startsWith("en")
        ? "en-US"
        : "zh-CN";
  }

  private Map<String, Object> verdictMap(LlmJudgeGrader.JudgeResult verdict) {
    var map = new java.util.LinkedHashMap<String, Object>();
    map.put("mode", verdict.mode());
    map.put("score", verdict.score());
    map.put("passed", verdict.passed());
    map.put("rubricChecks", verdict.rubricChecks());
    map.put("summary", verdict.summary());
    map.put("raw", verdict.raw());
    map.put("inputTokens", verdict.inputTokens());
    map.put("outputTokens", verdict.outputTokens());
    map.put("costUsd", verdict.costUsd());
    map.put("error", verdict.error());
    return map;
  }

  private RunView toView(EvalLabRunEntity row) {
    return new RunView(
        row.getId(),
        row.getStatus(),
        row.getSourceKind(),
        row.getSourceKey(),
        read(row.getCategoriesJson(), new TypeReference<List<String>>() {}),
        read(row.getConfigJson(), new TypeReference<Map<String, Object>>() {}),
        row.getSummaryJson() == null
            ? null
            : read(row.getSummaryJson(), new TypeReference<Map<String, Object>>() {}),
        row.isJudgeEnabled(),
        row.getTotalCostUsd(),
        row.getTotalTokens(),
        row.getTotalDurationMs(),
        row.getErrorMessage(),
        row.getLocale(),
        row.getCreatedAt(),
        row.getStartedAt(),
        row.getFinishedAt());
  }

  private CaseView toCaseView(EvalLabCaseResultEntity row) {
    return new CaseView(
        row.getId(),
        row.getCategory(),
        row.getSampleId(),
        row.getSampleTitleZh(),
        row.getSampleTitleEn(),
        row.getSourcePack(),
        row.getRepeatIndex(),
        row.getLinkedRunId(),
        row.isPassed(),
        row.getCodeScore(),
        row.getJudgeScore(),
        readMap(row.getMetricsJson()),
        readList(row.getAssertionsJson()),
        row.getJudgeVerdictJson() == null ? Map.of() : readMap(row.getJudgeVerdictJson()),
        row.getTraceSnapshotJson() == null ? Map.of() : readMap(row.getTraceSnapshotJson()),
        row.getRawOutput(),
        row.getDurationMs(),
        row.getInputTokens(),
        row.getOutputTokens(),
        row.getCostUsd(),
        row.getCreatedAt());
  }

  private Map<String, Object> readMap(String value) {
    return read(value, new TypeReference<Map<String, Object>>() {});
  }

  private List<Map<String, Object>> readList(String value) {
    return read(value, new TypeReference<List<Map<String, Object>>>() {});
  }

  private <T> T read(String value, TypeReference<T> type) {
    try {
      return json.readValue(value == null ? "{}" : value, type);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("评测数据无法解析。", e);
    }
  }

  private String write(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("评测数据无法序列化。", e);
    }
  }

  private static String sha(String text) {
    try {
      return java.util.HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.strip();
  }

  private static String safe(String value) {
    if (value == null || value.isBlank()) return "未知错误。";
    return value.length() <= 900 ? value : value.substring(0, 899) + "…";
  }

  private static String trim(String value, int limit) {
    if (value == null) return "";
    return value.length() <= limit ? value : value.substring(0, limit - 1) + "…";
  }

  private static double round(double value) {
    return Math.round(value * 1_000_000.0) / 1_000_000.0;
  }

  /** 简单的可变 Map 包装，避免为一次性聚合引入额外 DTO。 */
  private static final class LinkedHashMapCopy extends java.util.LinkedHashMap<String, Object> {
    LinkedHashMapCopy(Map<String, Object> source) {
      super(source);
    }
  }
}
