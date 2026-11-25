package dev.workbench.evaluation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.workbench.audit.AuditService;
import dev.workbench.domain.ExecutionScope;
import dev.workbench.evaluation.dataset.BuiltInMiniPackLoader;
import dev.workbench.evaluation.grader.CodeAssertionGrader;
import dev.workbench.evaluation.grader.LlmJudgeGrader;
import dev.workbench.framework.evaluation.EvalAnswerModel;
import dev.workbench.framework.evaluation.EvalCaseExecution;
import dev.workbench.framework.evaluation.EvalCategory;
import dev.workbench.framework.evaluation.EvalCategoryRunner;
import dev.workbench.framework.evaluation.EvalSample;
import dev.workbench.modelconnection.ModelConnectionService;
import dev.workbench.ownership.OwnershipService;
import dev.workbench.persistence.entity.EvalLabCaseResultEntity;
import dev.workbench.persistence.entity.EvalLabRunEntity;
import dev.workbench.persistence.repository.EvalLabCaseResultRepository;
import dev.workbench.persistence.repository.EvalLabRunRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** 评测裁判治理测试：验证终态费用含裁判费用、裁判异常 fail-closed 与裁判前预算门控。 */
class EvalLabJudgeGovernanceTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void finishIncludesJudgeCostInRunTotalCostAndSummary() throws Exception {
    var execution =
        new EvalCaseExecution(
            Map.of("ok", true),
            Map.of("ok", true),
            Map.of(),
            null,
            "model output",
            100L,
            20,
            10,
            0.12);
    var verdict =
        new LlmJudgeGrader.JudgeResult(
            "BYOK", 5, true, List.of(), "ok", "{}", 10, 5, 0.08, null);
    var fixture = createFixture(execution, verdict);

    String runId =
        fixture.service.start(
            new EvalLabService.StartRequest(
                "MINI",
                null,
                List.of("CONTEXT_COMPACTION"),
                "mock",
                null,
                true,
                true,
                true,
                false,
                1,
                1,
                null,
                "judge-conn-1"),
            "zh-CN");

    EvalLabRunEntity run = fixture.savedRun.get();
    assertNotNull(run);
    assertEquals(runId, run.getId());
    assertEquals("COMPLETED", run.getStatus());
    assertEquals(0.20, run.getTotalCostUsd(), 1e-6, "终态总费用必须等于被测模型费用与裁判费用之和");
    assertEquals(45, run.getTotalTokens());
    Map<String, Object> summary =
        JSON.readValue(run.getSummaryJson(), new TypeReference<Map<String, Object>>() {});
    assertEquals(0.12, ((Number) summary.get("modelCostUsd")).doubleValue(), 1e-6);
    assertEquals(0.08, ((Number) summary.get("judgeCostUsd")).doubleValue(), 1e-6);
    assertTrue(fixture.savedCase.get().isPassed());
  }

  @Test
  void judgeErrorForcesFailClosedAndPreservesJudgeCost() throws Exception {
    var execution =
        new EvalCaseExecution(
            Map.of("ok", true),
            Map.of("ok", true),
            Map.of(),
            null,
            "model output",
            80L,
            15,
            5,
            0.04);
    var verdict = LlmJudgeGrader.JudgeResult.failed("JUDGE_HTTP_500", 8, 2, 0.01);
    var fixture = createFixture(execution, verdict);

    fixture.service.start(
        new EvalLabService.StartRequest(
            "MINI",
            null,
            List.of("CONTEXT_COMPACTION"),
            "mock",
            null,
            true,
            true,
            true,
            false,
            1,
            1,
            null,
            "judge-conn-1"),
        "zh-CN");

    EvalLabCaseResultEntity savedCase = fixture.savedCase.get();
    assertNotNull(savedCase);
    assertFalse(savedCase.isPassed(), "裁判报错/解析失败时必须 fail-closed");
    Map<String, Object> metrics =
        JSON.readValue(savedCase.getMetricsJson(), new TypeReference<Map<String, Object>>() {});
    assertEquals("JUDGE_ERROR", metrics.get("errorCode"));
    assertEquals(0.05, fixture.savedRun.get().getTotalCostUsd(), 1e-6);
  }

  @Test
  void preJudgeBudgetCheckSkipsJudgeWhenCaseExhaustsMaxCost() throws Exception {
    var execution =
        new EvalCaseExecution(
            Map.of("ok", true),
            Map.of("ok", true),
            Map.of(),
            null,
            "model output",
            50L,
            10,
            10,
            0.12);
    var fixture =
        createFixture(
            execution,
            new LlmJudgeGrader.JudgeResult("BYOK", 5, true, List.of(), "ok", "{}", 1, 1, 0.5, null));

    fixture.service.start(
        new EvalLabService.StartRequest(
            "MINI",
            null,
            List.of("CONTEXT_COMPACTION"),
            "mock",
            null,
            true,
            true,
            true,
            false,
            1,
            1,
            0.10,
            "judge-conn-1"),
        "zh-CN");

    verifyNoInteractions(fixture.judge);
    EvalLabCaseResultEntity savedCase = fixture.savedCase.get();
    assertNotNull(savedCase);
    Map<String, Object> metrics =
        JSON.readValue(savedCase.getMetricsJson(), new TypeReference<Map<String, Object>>() {});
    assertEquals("BUDGET_EXCEEDED", metrics.get("judgeSkippedReason"));
    assertEquals(0.12, fixture.savedRun.get().getTotalCostUsd(), 1e-6);
  }

  @Test
  void disabledJudgeDoesNotEmitBudgetExceededReason() throws Exception {
    var execution =
        new EvalCaseExecution(
            Map.of("ok", true),
            Map.of("ok", true),
            Map.of(),
            null,
            "model output",
            50L,
            10,
            10,
            0.12);
    var fixture = createFixture(execution, LlmJudgeGrader.JudgeResult.disabled());

    fixture.service.start(
        new EvalLabService.StartRequest(
            "MINI",
            null,
            List.of("CONTEXT_COMPACTION"),
            "mock",
            null,
            true,
            true,
            true,
            false,
            1,
            1,
            0.10,
            null),
        "zh-CN");

    verifyNoInteractions(fixture.judge);
    Map<String, Object> metrics =
        JSON.readValue(
            fixture.savedCase.get().getMetricsJson(), new TypeReference<Map<String, Object>>() {});
    assertFalse(metrics.containsKey("judgeSkippedReason"));
  }

  private static Fixture createFixture(
      EvalCaseExecution execution, LlmJudgeGrader.JudgeResult verdict) {
    EvalLabRunRepository runs = mock(EvalLabRunRepository.class);
    EvalLabCaseResultRepository cases = mock(EvalLabCaseResultRepository.class);
    BuiltInMiniPackLoader miniPacks = mock(BuiltInMiniPackLoader.class);
    EvalCategoryRunner runner = mock(EvalCategoryRunner.class);
    CodeAssertionGrader codeGrader = mock(CodeAssertionGrader.class);
    LlmJudgeGrader judge = mock(LlmJudgeGrader.class);
    EvalAnswerModel answerModel = mock(EvalAnswerModel.class);
    ModelConnectionService connections = mock(ModelConnectionService.class);
    EvalMetricsAggregator aggregator = new EvalMetricsAggregator();
    EvalBenchmarkHubService hub = mock(EvalBenchmarkHubService.class);
    EvalCustomDatasetService customDatasets = mock(EvalCustomDatasetService.class);
    OwnershipService ownership = mock(OwnershipService.class);
    AuditService audit = mock(AuditService.class);

    var savedRun = new AtomicReference<EvalLabRunEntity>();
    var savedCase = new AtomicReference<EvalLabCaseResultEntity>();
    when(ownership.scope()).thenReturn(ExecutionScope.LOCAL);
    when(runs.saveAndFlush(any()))
        .thenAnswer(
            inv -> {
              savedRun.set(inv.getArgument(0));
              return savedRun.get();
            });
    when(runs.findById(any())).thenAnswer(inv -> Optional.ofNullable(savedRun.get()));
    when(runs.save(any()))
        .thenAnswer(
            inv -> {
              savedRun.set(inv.getArgument(0));
              return savedRun.get();
            });
    when(cases.save(any()))
        .thenAnswer(
            inv -> {
              savedCase.set(inv.getArgument(0));
              return savedCase.get();
            });

    var sample =
        new EvalSample(
            "ctx-test-01",
            EvalCategory.CONTEXT_COMPACTION,
            "mini-test",
            "裁判测试样例",
            "judge test case",
            "summarize prompt",
            Map.of(),
            List.of(Map.of("type", "EQUALS", "expected", true)),
            List.of("rubric item"));
    when(miniPacks.loadAll()).thenReturn(List.of(sample));
    when(runner.category()).thenReturn(EvalCategory.CONTEXT_COMPACTION);
    when(runner.run(any(), any(), any())).thenReturn(execution);
    when(codeGrader.grade(any(), any()))
        .thenReturn(new CodeAssertionGrader.Graded(List.of(Map.of("passed", true)), 1.0, true));
    when(judge.judge(any(), any(), any(), any(), any())).thenReturn(verdict);

    var service =
        new EvalLabService(
            runs,
            cases,
            miniPacks,
            List.of(runner),
            codeGrader,
            judge,
            answerModel,
            connections,
            aggregator,
            hub,
            customDatasets,
            ownership,
            JSON,
            audit,
            new DirectExecutorService());
    return new Fixture(service, judge, savedRun, savedCase);
  }

  private record Fixture(
      EvalLabService service,
      LlmJudgeGrader judge,
      AtomicReference<EvalLabRunEntity> savedRun,
      AtomicReference<EvalLabCaseResultEntity> savedCase) {}

  /** 当前线程同步执行任务，使异步评测批次确定性完成。 */
  private static final class DirectExecutorService extends AbstractExecutorService {
    private boolean shutdown;

    @Override
    public void shutdown() {
      shutdown = true;
    }

    @Override
    public List<Runnable> shutdownNow() {
      shutdown = true;
      return List.of();
    }

    @Override
    public boolean isShutdown() {
      return shutdown;
    }

    @Override
    public boolean isTerminated() {
      return shutdown;
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) {
      return true;
    }

    @Override
    public void execute(Runnable command) {
      command.run();
    }
  }
}
