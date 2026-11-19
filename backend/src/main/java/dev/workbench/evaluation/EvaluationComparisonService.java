package dev.workbench.evaluation;

import dev.workbench.observability.AgentMetrics;
import dev.workbench.observability.RunMetricsService;
import dev.workbench.task.RunRecordService;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.stereotype.Service;

/** 根据已保存案例做快照对照；不会启动评测或推断差异原因。 */
@Service
public class EvaluationComparisonService {
  private final EvaluationRepository repository;
  private final RunRecordService runs;
  private final RunMetricsService metrics;
  private final com.fasterxml.jackson.databind.ObjectMapper json;

  public EvaluationComparisonService(
      EvaluationRepository repository,
      RunRecordService runs,
      RunMetricsService metrics,
      com.fasterxml.jackson.databind.ObjectMapper json) {
    this.repository = repository;
    this.runs = runs;
    this.metrics = metrics;
    this.json = json;
  }

  public EvaluationComparison compare(String baselineId, String candidateId) {
    if (baselineId == null || candidateId == null || baselineId.equals(candidateId))
      throw new IllegalArgumentException("请选择两个不同的评测批次。");
    var baseline = repository.get(baselineId);
    var candidate = repository.get(candidateId);
    boolean sameSuite = baseline.suite().equals(candidate.suite());
    boolean validBaseline = validDefinition(baseline);
    boolean validCandidate = validDefinition(candidate);
    String baselineBudget = budgetLabel(baseline);
    String candidateBudget = budgetLabel(candidate);
    boolean sameBudget = baselineBudget.equals(candidateBudget);
    boolean comparableDefinition =
        sameSuite
            && validBaseline
            && validCandidate
            && baseline.definitionSha256().equals(candidate.definitionSha256())
            && baseline.definitionJson().equals(candidate.definitionJson())
            && sameBudget;
    String comparabilityReason =
        !sameSuite
            ? "套件编码不同，不能按案例直接比较。"
            : !validBaseline
                ? "基线批次缺少有效定义快照或摘要校验失败。"
                : !validCandidate
                    ? "候选批次缺少有效定义快照或摘要校验失败。"
                    : !baseline.definitionSha256().equals(candidate.definitionSha256())
                            || !baseline.definitionJson().equals(candidate.definitionJson())
                        ? "评测定义快照不同，条件不可比。"
                        : !sameBudget
                            ? "评测预算不同：基线 " + baselineBudget + "，候选 " + candidateBudget
                                + "；结果差异可能来自额度变化，不能按质量改善解释。"
                            : "套件编码、评测定义快照和预算配置一致；代码、模型或环境仍可能不同。";
    Map<SeedCase, EvaluationCase> before = index(repository.cases(baselineId));
    Map<SeedCase, EvaluationCase> after = index(repository.cases(candidateId));
    var codes = EnumSet.noneOf(SeedCase.class);
    codes.addAll(before.keySet());
    codes.addAll(after.keySet());
    Map<UUID, CaseResources> resources = new java.util.HashMap<>();
    var cases =
        codes.stream()
            .map(
                code ->
                    new CaseComparison(
                        code,
                        code.title(),
                        before.get(code),
                        after.get(code),
                        outcome(comparableDefinition, before.get(code), after.get(code)),
                        resourceFor(before.get(code), resources),
                        resourceFor(after.get(code), resources)))
            .toList();
    return new EvaluationComparison(
        baseline, candidate, sameSuite, comparableDefinition, comparabilityReason, cases);
  }

  /** 无快照、摘要错误或快照不同均不能判断结果变化。 */
  private static boolean validDefinition(EvaluationBatch batch) {
    return batch.definitionJson() != null
        && batch.definitionSha256() != null
        && batch.definitionSha256().equals(SeedSuite.sha256(batch.definitionJson()));
  }

  private CaseResources resourceFor(EvaluationCase sample, Map<UUID, CaseResources> cache) {
    if (sample == null || sample.runId() == null) return null;
    return cache.computeIfAbsent(sample.runId(), this::loadResources);
  }

  private CaseResources loadResources(UUID runId) {
    try {
      var run = runs.runSnapshot(runId.toString());
      var stats = metrics.agents(runId.toString());
      Long elapsed =
          switch (run.status()) {
            case COMPLETED, FAILED, CANCELLED, LIMITED, BLOCKED -> run.elapsedMs();
            case PENDING, RUNNING, PAUSED, INTERRUPTED -> null;
          };
      return new CaseResources(
          runId,
          true,
          elapsed,
          stats.stream().mapToLong(AgentMetrics::modelCalls).sum(),
          stats.stream().mapToLong(AgentMetrics::toolCalls).sum(),
          sumKnown(stats, true),
          stats.stream().mapToLong(AgentMetrics::unknownInputCount).sum(),
          sumKnown(stats, false),
          stats.stream().mapToLong(AgentMetrics::unknownOutputCount).sum());
    } catch (EmptyResultDataAccessException | IllegalArgumentException e) {
      return new CaseResources(runId, false, null, null, null, null, null, null, null);
    }
  }

  private static Long sumKnown(java.util.List<AgentMetrics> stats, boolean input) {
    var values =
        stats.stream()
            .map(item -> input ? item.knownInputTokens() : item.knownOutputTokens())
            .filter(java.util.Objects::nonNull)
            .toList();
    return values.isEmpty() ? null : values.stream().mapToLong(Long::longValue).sum();
  }

  /** 预算配置只比较实际限制；操作者字段不参与可比性。 */
  private String budgetLabel(EvaluationBatch batch) {
    if (batch.configJson() == null) return "默认预算";
    try {
      var node = json.readTree(batch.configJson());
      return "轮次 "
          + value(node, "maxRounds")
          + "，工具 "
          + value(node, "maxTools")
          + "，秒 "
          + value(node, "maxSeconds");
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      return "预算配置无效";
    }
  }

  private static String value(com.fasterxml.jackson.databind.JsonNode node, String field) {
    return node.hasNonNull(field) ? node.get(field).asText() : "默认";
  }

  private static Map<SeedCase, EvaluationCase> index(java.util.List<EvaluationCase> cases) {
    Map<SeedCase, EvaluationCase> result = new EnumMap<>(SeedCase.class);
    for (var item : cases) {
      if (result.put(item.code(), item) != null) throw new IllegalStateException("评测案例编码重复。");
    }
    return result;
  }

  private static ComparisonOutcome outcome(
      boolean sameSuite, EvaluationCase before, EvaluationCase after) {
    if (!sameSuite || before == null || after == null) return ComparisonOutcome.INCONCLUSIVE;
    if (before.status() == CaseStatus.PASSED && after.status() == CaseStatus.PASSED)
      return ComparisonOutcome.UNCHANGED_PASS;
    if (before.status() == CaseStatus.FAILED && after.status() == CaseStatus.FAILED)
      return ComparisonOutcome.UNCHANGED_FAIL;
    if (before.status() == CaseStatus.FAILED && after.status() == CaseStatus.PASSED)
      return ComparisonOutcome.IMPROVED;
    if (before.status() == CaseStatus.PASSED && after.status() == CaseStatus.FAILED)
      return ComparisonOutcome.REGRESSED;
    return ComparisonOutcome.INCONCLUSIVE;
  }
}
